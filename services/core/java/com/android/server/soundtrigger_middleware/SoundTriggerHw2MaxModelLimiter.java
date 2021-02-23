/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_3.ModelParameterRange;
import android.hardware.soundtrigger.V2_3.Properties;
import android.hardware.soundtrigger.V2_3.RecognitionConfig;
import android.media.soundtrigger_middleware.Status;
import android.os.IHwBinder;
import android.os.RemoteException;

/**
 * This is a decorator around ISoundTriggerHw2, which implements enforcement of the maximum number
 * of models supported by the HAL, for HAL implementations older than V2.4 that do not support
 * rejection of model loading at the HAL layer.
 * Since preemptive model unloading has been introduced in V2.4, it should never be used in
 * conjunction with this class, hence we don't bother considering preemtive unloading when counting
 * the number of currently loaded models.
 */
public class SoundTriggerHw2MaxModelLimiter implements ISoundTriggerHw2 {
    private final @NonNull ISoundTriggerHw2 mDelegate;
    private final int mMaxModels;

    // This counter is used to enforce the maximum number of loaded models.
    private int mNumLoadedModels = 0;

    private GlobalCallback mGlobalCallback;

    public SoundTriggerHw2MaxModelLimiter(
            ISoundTriggerHw2 delegate, int maxModels) {
        mDelegate = delegate;
        this.mMaxModels = maxModels;
    }

    @Override
    public void reboot() {
        mDelegate.reboot();
    }

    @Override
    public void detach() {
        mDelegate.detach();
    }

    @Override
    public Properties getProperties() {
        return mDelegate.getProperties();
    }

    @Override
    public void registerCallback(GlobalCallback callback) {
        mGlobalCallback = callback;
        mDelegate.registerCallback(mGlobalCallback);
    }

    @Override
    public int loadSoundModel(ISoundTriggerHw.SoundModel soundModel, ModelCallback callback) {
        synchronized (this) {
            if (mNumLoadedModels == mMaxModels) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            int result = mDelegate.loadSoundModel(soundModel, callback);
            ++mNumLoadedModels;
            return result;
        }
    }

    @Override
    public int loadPhraseSoundModel(ISoundTriggerHw.PhraseSoundModel soundModel,
            ModelCallback callback) {
        synchronized (this) {
            if (mNumLoadedModels == mMaxModels) {
                throw new RecoverableException(Status.RESOURCE_CONTENTION);
            }
            int result = mDelegate.loadPhraseSoundModel(soundModel, callback);
            ++mNumLoadedModels;
            return result;
        }
    }

    @Override
    public void unloadSoundModel(int modelHandle) {
        boolean wasAtMaxCapacity;
        synchronized (this) {
            wasAtMaxCapacity = mNumLoadedModels-- == mMaxModels;
        }
        try {
            mDelegate.unloadSoundModel(modelHandle);
        } catch (Exception e) {
            synchronized (this) {
                ++mNumLoadedModels;
            }
            throw e;
        }
        if (wasAtMaxCapacity) {
            // It is legal to invoke callbacks from within unloadSoundModel().
            // See README.md for details.
            mGlobalCallback.tryAgain();
        }
    }

    @Override
    public void stopRecognition(int modelHandle) {
        mDelegate.stopRecognition(modelHandle);
    }

    @Override
    public void startRecognition(int modelHandle, RecognitionConfig config) {
        mDelegate.startRecognition(modelHandle, config);
    }

    @Override
    public void getModelState(int modelHandle) {
        mDelegate.getModelState(modelHandle);
    }

    @Override
    public int getModelParameter(int modelHandle, int param) {
        return mDelegate.getModelParameter(modelHandle, param);
    }

    @Override
    public void setModelParameter(int modelHandle, int param, int value) {
        mDelegate.setModelParameter(modelHandle, param, value);
    }

    @Override
    public ModelParameterRange queryParameter(int modelHandle, int param) {
        return mDelegate.queryParameter(modelHandle, param);
    }

    @Override
    public boolean linkToDeath(IHwBinder.DeathRecipient recipient, long cookie) {
        return mDelegate.linkToDeath(recipient, cookie);
    }

    @Override
    public boolean unlinkToDeath(IHwBinder.DeathRecipient recipient) {
        return mDelegate.unlinkToDeath(recipient);
    }

    @Override
    public String interfaceDescriptor() throws RemoteException {
        return mDelegate.interfaceDescriptor();
    }

    @Override
    public void flushCallbacks() {
        mDelegate.flushCallbacks();
    }
}
