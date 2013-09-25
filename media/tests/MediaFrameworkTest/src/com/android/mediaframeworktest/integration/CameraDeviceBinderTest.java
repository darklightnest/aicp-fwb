/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.integration;

import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.ICameraDeviceCallbacks;
import android.hardware.camera2.ICameraDeviceUser;
import android.hardware.camera2.impl.CameraMetadataNative;
import android.hardware.camera2.utils.BinderHolder;
import android.os.RemoteException;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.view.Surface;

import static android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW;

import com.android.mediaframeworktest.MediaFrameworkIntegrationTestRunner;

import org.mockito.ArgumentMatcher;
import static org.mockito.Mockito.*;

public class CameraDeviceBinderTest extends AndroidTestCase {
    private static String TAG = "CameraDeviceBinderTest";
    // Number of streaming callbacks need to check.
    private static int NUM_CALLBACKS_CHECKED = 10;
    // Wait for capture result timeout value: 1500ms
    private final static int WAIT_FOR_COMPLETE_TIMEOUT_MS = 1500;

    private int mCameraId;
    private ICameraDeviceUser mCameraUser;
    private CameraBinderTestUtils mUtils;
    private ICameraDeviceCallbacks.Stub mMockCb;
    private Surface mSurface;
    // Need hold a Surfacetexture reference during a test execution, otherwise,
    // it could be GCed during a test, which causes camera run into bad state.
    private SurfaceTexture mSurfaceTexture;

    public CameraDeviceBinderTest() {
    }

    public class DummyCameraDeviceCallbacks extends ICameraDeviceCallbacks.Stub {

        @Override
        public void notifyCallback(int msgType, int ext1, int ext2) throws RemoteException {
        }

        @Override
        public void onResultReceived(int frameId, CameraMetadataNative result) throws RemoteException {
        }
    }

    class IsMetadataNotEmpty extends ArgumentMatcher<CameraMetadataNative> {
        @Override
        public boolean matches(Object obj) {
            return !((CameraMetadataNative) obj).isEmpty();
        }
     }

    private void createDefaultSurface() {
        mSurfaceTexture = new SurfaceTexture(/* ignored */0);
        mSurfaceTexture.setDefaultBufferSize(640, 480);
        mSurface = new Surface(mSurfaceTexture);
    }

    private CaptureRequest.Builder createDefaultBuilder(boolean needStream) throws Exception {
        CameraMetadataNative metadata = new CameraMetadataNative();
        assertTrue(metadata.isEmpty());

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

        CaptureRequest.Builder request = new CaptureRequest.Builder(metadata);
        assertFalse(request.isEmpty());
        assertFalse(metadata.isEmpty());
        if (needStream) {
            int streamId = mCameraUser.createStream(/* ignored */10, /* ignored */20,
                    /* ignored */30, mSurface);
            assertEquals(0, streamId);
            request.addTarget(mSurface);
        }
        return request;
    }

    private int submitCameraRequest(CaptureRequest request, boolean streaming) throws Exception {
        int requestId = mCameraUser.submitRequest(request, streaming);
        assertTrue("Request IDs should be non-negative", requestId >= 0);
        return requestId;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        /**
         * Workaround for mockito and JB-MR2 incompatibility
         *
         * Avoid java.lang.IllegalArgumentException: dexcache == null
         * https://code.google.com/p/dexmaker/issues/detail?id=2
         */
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().toString());
        mUtils = new CameraBinderTestUtils(getContext());

        // This cannot be set in the constructor, since the onCreate isn't
        // called yet
        mCameraId = MediaFrameworkIntegrationTestRunner.mCameraId;

        ICameraDeviceCallbacks.Stub dummyCallbacks = new DummyCameraDeviceCallbacks();

        String clientPackageName = getContext().getPackageName();

        mMockCb = spy(dummyCallbacks);

        BinderHolder holder = new BinderHolder();
        mUtils.getCameraService().connectDevice(mMockCb, mCameraId,
                clientPackageName, CameraBinderTestUtils.USE_CALLING_UID, holder);
        mCameraUser = ICameraDeviceUser.Stub.asInterface(holder.getBinder());
        assertNotNull(String.format("Camera %s was null", mCameraId), mCameraUser);
        createDefaultSurface();

        Log.v(TAG, String.format("Camera %s connected", mCameraId));
    }

    @Override
    protected void tearDown() throws Exception {
        mCameraUser.disconnect();
        mCameraUser = null;
        mSurface.release();
        mSurfaceTexture.release();
    }

    @SmallTest
    public void testCreateDefaultRequest() throws Exception {
        CameraMetadataNative metadata = new CameraMetadataNative();
        assertTrue(metadata.isEmpty());

        int status = mCameraUser.createDefaultRequest(TEMPLATE_PREVIEW, /* out */metadata);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        assertFalse(metadata.isEmpty());

    }

    @SmallTest
    public void testCreateStream() throws Exception {
        int streamId = mCameraUser.createStream(/* ignored */10, /* ignored */20, /* ignored */30,
                mSurface);
        assertEquals(0, streamId);

        assertEquals(CameraBinderTestUtils.ALREADY_EXISTS,
                mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0, mSurface));

        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId));
    }

    @SmallTest
    public void testDeleteInvalidStream() throws Exception {
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(-1));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(0));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(1));
        assertEquals(CameraBinderTestUtils.BAD_VALUE, mCameraUser.deleteStream(0xC0FFEE));
    }

    @SmallTest
    public void testCreateStreamTwo() throws Exception {

        // Create first stream
        int streamId = mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0,
                mSurface);
        assertEquals(0, streamId);

        assertEquals(CameraBinderTestUtils.ALREADY_EXISTS,
                mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0, mSurface));

        // Create second stream with a different surface.
        SurfaceTexture surfaceTexture = new SurfaceTexture(/* ignored */0);
        surfaceTexture.setDefaultBufferSize(640, 480);
        Surface surface2 = new Surface(surfaceTexture);

        int streamId2 = mCameraUser.createStream(/* ignored */0, /* ignored */0, /* ignored */0,
                surface2);
        assertEquals(1, streamId2);

        // Clean up streams
        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId));
        assertEquals(CameraBinderTestUtils.NO_ERROR, mCameraUser.deleteStream(streamId2));
    }

    @SmallTest
    public void testSubmitBadRequest() throws Exception {

        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */false);
        CaptureRequest request1 = builder.build();
        int status = mCameraUser.submitRequest(request1, /* streaming */false);
        assertEquals("Expected submitRequest to return BAD_VALUE " +
                "since we had 0 surface targets set.", CameraBinderTestUtils.BAD_VALUE, status);

        builder.addTarget(mSurface);
        CaptureRequest request2 = builder.build();
        status = mCameraUser.submitRequest(request2, /* streaming */false);
        assertEquals("Expected submitRequest to return BAD_VALUE since " +
                "the target surface wasn't registered with createStream.",
                CameraBinderTestUtils.BAD_VALUE, status);
    }

    @SmallTest
    public void testSubmitGoodRequest() throws Exception {

        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);
        CaptureRequest request = builder.build();

        // Submit valid request twice.
        int requestId1 = submitCameraRequest(request, /* streaming */false);
        int requestId2 = submitCameraRequest(request, /* streaming */false);
        assertNotSame("Request IDs should be unique for multiple requests", requestId1, requestId2);

    }

    @SmallTest
    public void testSubmitStreamingRequest() throws Exception {

        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);

        CaptureRequest request = builder.build();

        // Submit valid request once (non-streaming), and another time
        // (streaming)
        int requestId1 = submitCameraRequest(request, /* streaming */false);

        int requestIdStreaming = submitCameraRequest(request, /* streaming */true);
        assertNotSame("Request IDs should be unique for multiple requests", requestId1,
                requestIdStreaming);

        int status = mCameraUser.cancelRequest(-1);
        assertEquals("Invalid request IDs should not be cancellable",
                CameraBinderTestUtils.BAD_VALUE, status);

        status = mCameraUser.cancelRequest(requestId1);
        assertEquals("Non-streaming request IDs should not be cancellable",
                CameraBinderTestUtils.BAD_VALUE, status);

        status = mCameraUser.cancelRequest(requestIdStreaming);
        assertEquals("Streaming request IDs should be cancellable", CameraBinderTestUtils.NO_ERROR,
                status);

    }

    @SmallTest
    public void testCameraInfo() throws RemoteException {
        CameraMetadataNative info = new CameraMetadataNative();

        int status = mCameraUser.getCameraInfo(/*out*/info);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        assertFalse(info.isEmpty());
        assertNotNull(info.get(CameraCharacteristics.SCALER_AVAILABLE_FORMATS));
    }

    @SmallTest
    public void testCameraCharacteristics() throws RemoteException {
        CameraMetadataNative info = new CameraMetadataNative();

        int status = mUtils.getCameraService().getCameraCharacteristics(mCameraId, /*out*/info);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        assertFalse(info.isEmpty());
        assertNotNull(info.get(CameraCharacteristics.SCALER_AVAILABLE_FORMATS));
    }

    @SmallTest
    public void testWaitUntilIdle() throws Exception {
        CaptureRequest.Builder builder = createDefaultBuilder(/* needStream */true);
        int requestIdStreaming = submitCameraRequest(builder.build(), /* streaming */true);

        // Test Bad case first: waitUntilIdle when there is active repeating request
        int status = mCameraUser.waitUntilIdle();
        assertEquals("waitUntilIdle is invalid operation when there is active repeating request",
            CameraBinderTestUtils.INVALID_OPERATION, status);

        // Test good case, waitUntilIdle when there is no active repeating request
        status = mCameraUser.cancelRequest(requestIdStreaming);
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
        status = mCameraUser.waitUntilIdle();
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);
    }

    @SmallTest
    public void testCaptureResultCallbacks() throws Exception {
        IsMetadataNotEmpty matcher = new IsMetadataNotEmpty();
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        // Test both single request and streaming request.
        int requestId1 = submitCameraRequest(request, /* streaming */false);
        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).times(1)).onResultReceived(
                eq(requestId1),
                argThat(matcher));

        int streamingId = submitCameraRequest(request, /* streaming */true);
        verify(mMockCb, timeout(WAIT_FOR_COMPLETE_TIMEOUT_MS).atLeast(NUM_CALLBACKS_CHECKED))
                .onResultReceived(
                        eq(streamingId),
                        argThat(matcher));
    }

    @SmallTest
    public void testFlush() throws Exception {
        int status;

        // Initial flush should work
        status = mCameraUser.flush();
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        // Then set up a stream
        CaptureRequest request = createDefaultBuilder(/* needStream */true).build();

        // Flush should still be a no-op, really
        status = mCameraUser.flush();
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        // Submit a few capture requests
        int requestId1 = submitCameraRequest(request, /* streaming */false);
        int requestId2 = submitCameraRequest(request, /* streaming */false);
        int requestId3 = submitCameraRequest(request, /* streaming */false);
        int requestId4 = submitCameraRequest(request, /* streaming */false);
        int requestId5 = submitCameraRequest(request, /* streaming */false);

        // Then flush
        status = mCameraUser.flush();
        assertEquals(CameraBinderTestUtils.NO_ERROR, status);

        // TODO: When errors are hooked up, count that errors + successful
        // requests equal to 5.
    }
}
