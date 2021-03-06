/*
 * Copyright 2015-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.js;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.google.common.collect.ImmutableSet;

public class ReactNativeFlavors {

  // Utility class, do not instantiate.
  private ReactNativeFlavors() { }

  public static final Flavor UNBUNDLE = ImmutableFlavor.of("unbundle");

  public static final Flavor DEV = ImmutableFlavor.of("dev");

  public static final Flavor DO_NOT_BUNDLE = ImmutableFlavor.of("rn_no_bundle");

  public static boolean validateFlavors(ImmutableSet<Flavor> flavors) {
    return ImmutableSet.of(DEV, UNBUNDLE).containsAll(flavors);
  }

  public static boolean useUnbundling(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(UNBUNDLE);
  }

  public static boolean isDevMode(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(DEV);
  }

  public static boolean skipBundling(BuildTarget buildTarget) {
    return buildTarget.getFlavors().contains(DO_NOT_BUNDLE);
  }
}
