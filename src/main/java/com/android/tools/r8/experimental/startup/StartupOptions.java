// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.experimental.startup;

public class StartupOptions {

  private boolean enableMinimalStartupDex = false;

  private StartupConfiguration startupConfiguration;

  public boolean isMinimalStartupDexEnabled() {
    return enableMinimalStartupDex;
  }

  public void setEnableMinimalStartupDex() {
    enableMinimalStartupDex = true;
  }

  public boolean hasStartupConfiguration() {
    return startupConfiguration != null;
  }

  public StartupConfiguration getStartupConfiguration() {
    return startupConfiguration;
  }

  public void setStartupConfiguration(StartupConfiguration startupConfiguration) {
    this.startupConfiguration = startupConfiguration;
  }
}
