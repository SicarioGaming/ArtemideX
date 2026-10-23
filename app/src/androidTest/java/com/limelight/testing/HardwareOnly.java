package com.limelight.testing;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A test that needs real hardware and can't check for it first. device-test.sh leaves these out
 * on an emulator (-e notAnnotation com.limelight.testing.HardwareOnly). Prefer
 * DeviceCaps.assumeHardware where the test can check: the skip then says what was missing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface HardwareOnly {
    /** What the test needs, e.g. "a paired PC that is online". */
    String value();
}
