/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.locks;

import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.feature.AutomaticallyRegisteredFeature;
import com.oracle.svm.core.feature.InternalFeature;
import com.oracle.svm.core.util.VMError;

import static com.oracle.svm.core.Uninterruptible.CALLED_FROM_UNINTERRUPTIBLE_CODE;

/**
 * Support of {@link VMMutex}, {@link VMCondition} and {@link VMSemaphore} in single-threaded
 * environments. No real locking is necessary.
 */
final class SingleThreadedVMLockSupport extends VMLockSupport {
    @Override
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public VMMutex[] getMutexes() {
        return null;
    }

    @Override
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public VMCondition[] getConditions() {
        return null;
    }

    @Override
    @Uninterruptible(reason = CALLED_FROM_UNINTERRUPTIBLE_CODE, mayBeInlined = true)
    public VMSemaphore[] getSemaphores() {
        return null;
    }
}

@AutomaticallyRegisteredFeature
final class SingleThreadedVMLockFeature implements InternalFeature {

    private final ClassInstanceReplacer<VMMutex, VMMutex> mutexReplacer = new ClassInstanceReplacer<>(VMMutex.class) {
        @Override
        protected VMMutex createReplacement(VMMutex source) {
            return new SingleThreadedVMMutex(source.getName());
        }
    };

    private final ClassInstanceReplacer<VMCondition, VMCondition> conditionReplacer = new ClassInstanceReplacer<>(VMCondition.class) {
        @Override
        protected VMCondition createReplacement(VMCondition source) {
            return new SingleThreadedVMCondition((SingleThreadedVMMutex) mutexReplacer.apply(source.getMutex()));
        }
    };

    private final ClassInstanceReplacer<VMSemaphore, VMSemaphore> semaphoreReplacer = new ClassInstanceReplacer<>(VMSemaphore.class) {
        @Override
        protected VMSemaphore createReplacement(VMSemaphore source) {
            return new SingleThreadedVMSemaphore(source.getName());
        }
    };

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return !SubstrateOptions.MultiThreaded.getValue();
    }

    @Override
    public void duringSetup(DuringSetupAccess access) {
        ImageSingletons.add(VMLockSupport.class, new SingleThreadedVMLockSupport());
        access.registerObjectReplacer(mutexReplacer);
        access.registerObjectReplacer(conditionReplacer);
        access.registerObjectReplacer(semaphoreReplacer);
    }

    @Override
    public void beforeCompilation(BeforeCompilationAccess access) {
        /* Seal the lists. */
        mutexReplacer.getReplacements();
        conditionReplacer.getReplacements();
        semaphoreReplacer.getReplacements();
    }
}

final class SingleThreadedVMMutex extends VMMutex {
    @Platforms(Platform.HOSTED_ONLY.class)
    SingleThreadedVMMutex(String name) {
        super(name);
    }

    @Override
    @Uninterruptible(reason = "Too early for safepoints.")
    public int initialize() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    @Uninterruptible(reason = "The isolate teardown is in progress.")
    public int destroy() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    public VMMutex lock() {
        assert !isOwner() : "Recursive locking is not supported";
        setOwnerToCurrentThread();
        return this;
    }

    @Override
    @Uninterruptible(reason = "Whole critical section needs to be uninterruptible.", callerMustBe = true)
    public void lockNoTransition() {
        assert !isOwner() : "Recursive locking is not supported";
        setOwnerToCurrentThread();
    }

    @Override
    @Uninterruptible(reason = "Whole critical section needs to be uninterruptible.", callerMustBe = true)
    public void lockNoTransitionUnspecifiedOwner() {
        setOwnerToUnspecified();
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void unlock() {
        clearCurrentThreadOwner();
    }

    @Override
    @Uninterruptible(reason = "Whole critical section needs to be uninterruptible.")
    public void unlockNoTransitionUnspecifiedOwner() {
        clearUnspecifiedOwner();
    }
}

final class SingleThreadedVMCondition extends VMCondition {

    SingleThreadedVMCondition(SingleThreadedVMMutex mutex) {
        super(mutex);
    }

    @Override
    @Uninterruptible(reason = "Too early for safepoints.")
    public int initialize() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    @Uninterruptible(reason = "The isolate teardown is in progress.")
    public int destroy() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    public void block() {
        VMError.shouldNotReachHere("Cannot block in a single-threaded environment, because there is no other thread that could signal");
    }

    @Uninterruptible(reason = "Should only be called if the thread did an explicit transition to native earlier.", callerMustBe = true)
    @Override
    public void blockNoTransition() {
        VMError.shouldNotReachHere("Cannot block in a single-threaded environment, because there is no other thread that could signal");
    }

    @Uninterruptible(reason = "Should only be called if the thread did an explicit transition to native earlier.", callerMustBe = true)
    @Override
    public void blockNoTransitionUnspecifiedOwner() {
        VMError.shouldNotReachHere("Cannot block in a single-threaded environment, because there is no other thread that could signal");
    }

    @Override
    public long block(long nanos) {
        VMError.shouldNotReachHere("Cannot block in a single-threaded environment, because there is no other thread that could signal");
        return 0;
    }

    @Uninterruptible(reason = "Should only be called if the thread did an explicit transition to native earlier.", callerMustBe = true)
    @Override
    public long blockNoTransition(long nanos) {
        VMError.shouldNotReachHere("Cannot block in a single-threaded environment, because there is no other thread that could signal");
        return 0;
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void signal() {
        /* Nothing to do. */
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void broadcast() {
        /* Nothing to do. */
    }
}

final class SingleThreadedVMSemaphore extends VMSemaphore {

    @Platforms(Platform.HOSTED_ONLY.class)
    SingleThreadedVMSemaphore(String name) {
        super(name);
    }

    @Override
    @Uninterruptible(reason = "Too early for safepoints.")
    public int initialize() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    @Uninterruptible(reason = "The isolate teardown is in progress.")
    public int destroy() {
        /* Nothing to do here. */
        return 0;
    }

    @Override
    public void await() {
        VMError.shouldNotReachHere("Cannot wait in a single-threaded environment, because there is no other thread that could signal.");
    }

    @Override
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public void signal() {
        /* Nothing to do here. */
    }
}
