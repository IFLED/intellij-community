/*
 * Copyright 2000-2005 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.application;

import org.jetbrains.annotations.NonNls;

import java.util.ResourceBundle;

import com.intellij.CommonBundle;

/**
 * Provides access to localized properties for the application component of IDEA.
 */
public class ApplicationBundle {
  private static final ResourceBundle ourBundle = ResourceBundle.getBundle("com.intellij.openapi.application.ApplicationBundle");

  private ApplicationBundle() {}

  public static String message(@NonNls  String key, Object... params) {
    return CommonBundle.message(ourBundle, key, params);
  }
}
