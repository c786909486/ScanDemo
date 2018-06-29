/*
 * Copyright (C) 2010 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ckz.qrcodescan.zxing.camera;

import android.graphics.Point;
import android.hardware.Camera;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class CameraConfigurationManager {
	private static final String TAG = CameraConfigurationManager.class
			.getSimpleName();

	private static final int TEN_DESIRED_ZOOM = 27;

	private static final Pattern COMMA_PATTERN = Pattern.compile(",");

	private Point screenResolution;
	private Point cameraResolution;
	private int previewFormat;
	private String previewFormatString;

	CameraConfigurationManager() {
	}

	/**
	 * Reads, one time, values from the camera that are needed by the app.
	 */
	void initFromCameraParameters(Camera camera,int w,int h) {
		Camera.Parameters parameters = camera.getParameters();
		previewFormat = parameters.getPreviewFormat();
		initCameraParameters(parameters,w,h);
		previewFormatString = parameters.get("preview-format");
		Log.d(TAG, "Default preview format: " + previewFormat + '/'
				+ previewFormatString);
		screenResolution = new Point(w,
				h);
		Log.d(TAG, "Screen resolution: " + screenResolution);
		// 为竖屏添加
		Point screenResolutionForCamera = new Point();
		screenResolutionForCamera.x = screenResolution.x;
		screenResolutionForCamera.y = screenResolution.y;
		if (screenResolution.x < screenResolution.y) {
			screenResolutionForCamera.x = screenResolution.y;
			screenResolutionForCamera.y = screenResolution.x;
		}
		// 下句第二参数要根据竖屏修改
		cameraResolution = getCameraResolution(parameters,
				screenResolutionForCamera);
//		cameraResolution = getCameraResolution(parameters, screenResolution);
		Log.d(TAG, "Camera resolution: " + cameraResolution);
	}

	/**
	 * Sets the camera up to take preview images which are used for both preview
	 * and decoding. We detect the preview format here so that
	 * buildLuminanceSource() can build an appropriate LuminanceSource subclass.
	 * In the future we may want to force YUV420SP as it's the smallest, and the
	 * planar Y can be used for barcode scanning without a copy in some cases.
	 */
	void setDesiredCameraParameters(Camera camera) {
		Camera.Parameters parameters = camera.getParameters();
		Log.d(TAG, "Setting preview size: " + cameraResolution);
		parameters.setPreviewSize(cameraResolution.x, cameraResolution.y);
		setFlash(parameters);
		setZoom(parameters);
		// setSharpness(parameters);

		camera.setParameters(parameters);
		// ���������ת90��
		// camera.setDisplayOrientation(90);
		setDisplayOrientation(camera, 90);
	}

	private void initCameraParameters( Camera.Parameters parameters, int width, int height) {

		List<Camera.Size> pictureSizes = parameters.getSupportedPictureSizes();
		List<Camera.Size> previewSizes = parameters.getSupportedPreviewSizes();
		if (!isEmpty(pictureSizes) && !isEmpty(previewSizes)) {

			Camera.Size optimalPicSize = getOptimalCameraSize(pictureSizes, width, height);
			Camera.Size optimalPreSize = getOptimalCameraSize(previewSizes, width, height);

//            Log.i(TAG,"TextureSize "+width+" "+height+" optimalSize pic " + optimalPicSize.width + " " + optimalPicSize.height + " pre " + optimalPreSize.width + " " + optimalPreSize.height);
			parameters.setPictureSize(optimalPicSize.width, optimalPicSize.height);
			parameters.setPreviewSize(optimalPreSize.width, optimalPreSize.height);

		}

	}

	/**
	 *
	 * @param sizes 相机support参数
	 * @param w
	 * @param h
	 * @return 最佳Camera size
	 */
	private Camera.Size getOptimalCameraSize(List<Camera.Size> sizes, int w, int h){
		sortCameraSize(sizes);
		int position = binarySearch(sizes, w*h);
		return sizes.get(position);
	}

	/**
	 *
	 * @param sizes
	 * @param targetNum 要比较的数
	 * @return
	 */
	private int binarySearch(List<Camera.Size> sizes,int targetNum){
		int targetIndex;
		int left = 0,right;
		int length = sizes.size();
		for (right = length-1;left != right;){
			int midIndex = (right + left)/2;
			int mid = right - left;
			Camera.Size size = sizes.get(midIndex);
			int midValue = size.width * size.height;
			if (targetNum == midValue){
				return midIndex;
			}
			if (targetNum > midValue){
				left = midIndex;
			}else {
				right = midIndex;
			}

			if (mid <= 1){
				break;
			}
		}
		Camera.Size rightSize = sizes.get(right);
		Camera.Size leftSize = sizes.get(left);
		int rightNum = rightSize.width * rightSize.height;
		int leftNum = leftSize.width * leftSize.height;
		targetIndex = Math.abs((rightNum - leftNum)/2) > Math.abs(rightNum - targetNum) ? right : left;
		return targetIndex;
	}

	/**
	 * 排序
	 * @param previewSizes
	 */
	private void sortCameraSize(List<Camera.Size> previewSizes){
		Collections.sort(previewSizes, new Comparator<Camera.Size>() {
			@Override
			public int compare(Camera.Size size1, Camera.Size size2) {
				int compareHeight = size1.height - size2.height;
				if (compareHeight == 0){
					return (size1.width == size2.width ? 0 :(size1.width > size2.width ? 1:-1));
				}
				return compareHeight;
			}
		});
	}

	/**
	 * 集合不为空
	 *
	 * @param list
	 * @param <E>
	 * @return
	 */
	private <E> boolean isEmpty(List<E> list) {
		return list == null || list.isEmpty();
	}

	public Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
		final double ASPECT_TOLERANCE = 0.1;
		double targetRatio = (double) w / h;
		if (sizes == null) return null;

		Camera.Size optimalSize = null;
		double minDiff = Double.MAX_VALUE;

		int targetHeight = h;

		// Try to find an size match aspect ratio and size
		for (Camera.Size size : sizes) {
			double ratio = (double) size.width / size.height;
			if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
			if (Math.abs(size.height - targetHeight) < minDiff) {
				optimalSize = size;
				minDiff = Math.abs(size.height - targetHeight);
			}
		}

		// Cannot find the one match the aspect ratio, ignore the requirement
		if (optimalSize == null) {
			minDiff = Double.MAX_VALUE;
			for (Camera.Size size : sizes) {
				if (Math.abs(size.height - targetHeight) < minDiff) {
					optimalSize = size;
					minDiff = Math.abs(size.height - targetHeight);
				}
			}
		}
		return optimalSize;
	}

	Point getCameraResolution() {
		return cameraResolution;
	}

	Point getScreenResolution() {
		return screenResolution;
	}

	int getPreviewFormat() {
		return previewFormat;
	}

	String getPreviewFormatString() {
		return previewFormatString;
	}

	private static Point getCameraResolution(Camera.Parameters parameters,
			Point screenResolution) {

		String previewSizeValueString = parameters.get("preview-size-values");
		// saw this on Xperia
		if (previewSizeValueString == null) {
			previewSizeValueString = parameters.get("preview-size-value");
		}

		Point cameraResolution = null;

		if (previewSizeValueString != null) {
			Log.d(TAG, "preview-size-values parameter: "
					+ previewSizeValueString);
			cameraResolution = findBestPreviewSizeValue(previewSizeValueString,
					screenResolution);
		}

		if (cameraResolution == null) {
			// Ensure that the camera resolution is a multiple of 8, as the
			// screen may not be.
			cameraResolution = new Point((screenResolution.x >> 3) << 3,
					(screenResolution.y >> 3) << 3);
		}

		return cameraResolution;
	}

	private static Point findBestPreviewSizeValue(
			CharSequence previewSizeValueString, Point screenResolution) {
		int bestX = 0;
		int bestY = 0;
		int diff = Integer.MAX_VALUE;
		for (String previewSize : COMMA_PATTERN.split(previewSizeValueString)) {

			previewSize = previewSize.trim();
			int dimPosition = previewSize.indexOf('x');
			if (dimPosition < 0) {
				Log.w(TAG, "Bad preview-size: " + previewSize);
				continue;
			}

			int newX;
			int newY;
			try {
				newX = Integer.parseInt(previewSize.substring(0, dimPosition));
				newY = Integer.parseInt(previewSize.substring(dimPosition + 1));
			} catch (NumberFormatException nfe) {
				Log.w(TAG, "Bad preview-size: " + previewSize);
				continue;
			}

			int newDiff = Math.abs(newX - screenResolution.x)
					+ Math.abs(newY - screenResolution.y);
			if (newDiff == 0) {
				bestX = newX;
				bestY = newY;
				break;
			} else if (newDiff < diff) {
				bestX = newX;
				bestY = newY;
				diff = newDiff;
			}

		}

		if (bestX > 0 && bestY > 0) {
			return new Point(bestX, bestY);
		}
		return null;
	}

	private static int findBestMotZoomValue(CharSequence stringValues,
			int tenDesiredZoom) {
		int tenBestValue = 0;
		for (String stringValue : COMMA_PATTERN.split(stringValues)) {
			stringValue = stringValue.trim();
			double value;
			try {
				value = Double.parseDouble(stringValue);
			} catch (NumberFormatException nfe) {
				return tenDesiredZoom;
			}
			int tenValue = (int) (10.0 * value);
			if (Math.abs(tenDesiredZoom - value) < Math.abs(tenDesiredZoom
					- tenBestValue)) {
				tenBestValue = tenValue;
			}
		}
		return tenBestValue;
	}

	private void setFlash(Camera.Parameters parameters) {
		// FIXME: This is a hack to turn the flash off on the Samsung Galaxy.
		// And this is a hack-hack to work around a different value on the
		// Behold II
		// Restrict Behold II check to Cupcake, per Samsung's advice
		// if (Build.MODEL.contains("Behold II") &&
		// CameraManager.SDK_INT == Build.VERSION_CODES.CUPCAKE) {
		if (Build.MODEL.contains("Behold II") && CameraManager.SDK_INT == 3) { // 3
																				// =
																				// Cupcake
			parameters.set("flash-value", 1);
		} else {
			parameters.set("flash-value", 2);
		}
		// This is the standard setting to turn the flash off that all devices
		// should honor.
		parameters.set("flash-mode", "off");
	}

	private void setZoom(Camera.Parameters parameters) {

		String zoomSupportedString = parameters.get("zoom-supported");
		if (zoomSupportedString != null
				&& !Boolean.parseBoolean(zoomSupportedString)) {
			return;
		}

		int tenDesiredZoom = TEN_DESIRED_ZOOM;

		String maxZoomString = parameters.get("max-zoom");
		if (maxZoomString != null) {
			try {
				int tenMaxZoom = (int) (10.0 * Double
						.parseDouble(maxZoomString));
				if (tenDesiredZoom > tenMaxZoom) {
					tenDesiredZoom = tenMaxZoom;
				}
			} catch (NumberFormatException nfe) {
				Log.w(TAG, "Bad max-zoom: " + maxZoomString);
			}
		}

		String takingPictureZoomMaxString = parameters
				.get("taking-picture-zoom-max");
		if (takingPictureZoomMaxString != null) {
			try {
				int tenMaxZoom = Integer.parseInt(takingPictureZoomMaxString);
				if (tenDesiredZoom > tenMaxZoom) {
					tenDesiredZoom = tenMaxZoom;
				}
			} catch (NumberFormatException nfe) {
				Log.w(TAG, "Bad taking-picture-zoom-max: "
						+ takingPictureZoomMaxString);
			}
		}

		String motZoomValuesString = parameters.get("mot-zoom-values");
		if (motZoomValuesString != null) {
			tenDesiredZoom = findBestMotZoomValue(motZoomValuesString,
					tenDesiredZoom);
		}

		String motZoomStepString = parameters.get("mot-zoom-step");
		if (motZoomStepString != null) {
			try {
				double motZoomStep = Double.parseDouble(motZoomStepString
						.trim());
				int tenZoomStep = (int) (10.0 * motZoomStep);
				if (tenZoomStep > 1) {
					tenDesiredZoom -= tenDesiredZoom % tenZoomStep;
				}
			} catch (NumberFormatException nfe) {
				// continue
			}
		}

		// Set zoom. This helps encourage the user to pull back.
		// Some devices like the Behold have a zoom parameter
		if (maxZoomString != null || motZoomValuesString != null) {
			parameters.set("zoom", String.valueOf(tenDesiredZoom / 10.0));
		}

		// Most devices, like the Hero, appear to expose this zoom parameter.
		// It takes on values like "27" which appears to mean 2.7x zoom
		if (takingPictureZoomMaxString != null) {
			parameters.set("taking-picture-zoom", tenDesiredZoom);
		}
	}

	/*
	 * private void setSharpness(Camera.Parameters parameters) { int
	 * desiredSharpness = DESIRED_SHARPNESS; String maxSharpnessString =
	 * parameters.get("sharpness-max"); if (maxSharpnessString != null) { try {
	 * int maxSharpness = Integer.parseInt(maxSharpnessString); if
	 * (desiredSharpness > maxSharpness) { desiredSharpness = maxSharpness; } }
	 * catch (NumberFormatException nfe) { Log.w(TAG, "Bad sharpness-max: " +
	 * maxSharpnessString); } } parameters.set("sharpness", desiredSharpness); }
	 */

	/* 改变照相机成像的方向的方法 */
	protected void setDisplayOrientation(Camera camera, int angle) {
		Method downPolymorphic = null;
		try {
			downPolymorphic = camera.getClass().getMethod(
					"setDisplayOrientation", new Class[] { int.class });
			if (downPolymorphic != null)
				downPolymorphic.invoke(camera, new Object[] { angle });
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (InvocationTargetException e) {
			e.printStackTrace();
		}

	}

}
