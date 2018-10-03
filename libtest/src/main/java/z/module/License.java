/**
 * Copyright 2013, Landz and its contributors. All rights reserved.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package z.module;

/*
 * #%L
 * libtest
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

/**
 *
 * refs:
 *   http://en.wikipedia.org/wiki/Comparison_of_free_software_licenses
 */
public enum License {

  APLv2(
      "http://www.apache.org/licenses/LICENSE-2.0",
      "Apache License, Version 2.0"),
  NewBSD(
      "http://opensource.org/licenses/BSD-3-Clause",
      "BSD 3-Clause License"),
  SimplifiedBSD(
      "http://opensource.org/licenses/BSD-2-Clause",
      "BSD 2-Clause License"),
  MIT(
      "http://opensource.org/licenses/mit-license.html",
      "MIT License"),
  EPLv1(
      "http://www.eclipse.org/org/documents/epl-v10.php",
      "Eclipse Public License - v 1.0"),
  GPLv3(
      "http://www.gnu.org/licenses/gpl.html",
      "GNU GENERAL PUBLIC LICENSE"),
  LGPLv3(
      "http://www.gnu.org/licenses/lgpl.html",
      "GNU LESSER GENERAL PUBLIC LICENSE"),

  UNKNOWN(
      "UNKNOWN",
      "UNKNOWN");

  private final String url;
  private final String description;

  License(String url, String description) {
    this.url = url;
    this.description = description;
  }

  public String getUrl() {
    return url;
  }
  public String getDescription() {
    return description;
  }

}
