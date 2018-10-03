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

package z.util;

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
 * Represents a {@linkplain System#getProperties() standard system property}.
 *
 * @author Kurt Alfred Kluever
 * @modified Landz
 */
public enum SystemProperty {

  /** Java Runtime Environment version. */
  JAVA_VERSION("java.version"),

  /** Java Runtime Environment vendor. */
  JAVA_VENDOR("java.vendor"),

  /** Java vendor URL. */
  JAVA_VENDOR_URL("java.vendor.url"),

  /** Java installation directory. */
  JAVA_HOME("java.home"),

  /** Java Virtual Machine specification version. */
  JAVA_VM_SPECIFICATION_VERSION("java.vm.specification.version"),

  /** Java Virtual Machine specification vendor. */
  JAVA_VM_SPECIFICATION_VENDOR("java.vm.specification.vendor"),

  /** Java Virtual Machine specification name. */
  JAVA_VM_SPECIFICATION_NAME("java.vm.specification.name"),

  /** Java Virtual Machine implementation version. */
  JAVA_VM_VERSION("java.vm.version"),

  /** Java Virtual Machine implementation vendor. */
  JAVA_VM_VENDOR("java.vm.vendor"),

  /** Java Virtual Machine implementation name. */
  JAVA_VM_NAME("java.vm.name"),

  /** Java Runtime Environment specification version. */
  JAVA_SPECIFICATION_VERSION("java.specification.version"),

  /** Java Runtime Environment specification vendor. */
  JAVA_SPECIFICATION_VENDOR("java.specification.vendor"),

  /** Java Runtime Environment specification name. */
  JAVA_SPECIFICATION_NAME("java.specification.name"),

  /** Java class format version number. */
  JAVA_CLASS_VERSION("java.class.version"),

  /** Java class path. */
  JAVA_CLASS_PATH("java.class.path"),

  /** List of paths to search when loading libraries. */
  JAVA_LIBRARY_PATH("java.library.path"),

  /** Default temp file path. */
  JAVA_IO_TMPDIR("java.io.tmpdir"),

  /** Name of JIT compiler to use. */
  JAVA_COMPILER("java.compiler"),

  /** Path of extension directory or directories. */
  JAVA_EXT_DIRS("java.ext.dirs"),

  /** Operating system name. */
  OS_NAME("os.name"),

  /** Operating system architecture. */
  OS_ARCH("os.arch"),

  /** Operating system version. */
  OS_VERSION("os.version"),

  /** File separator ("/" on UNIX). */
  FILE_SEPARATOR("file.separator"),

  /** Path separator (":" on UNIX). */
  PATH_SEPARATOR("path.separator"),

  /** Line separator ("\n" on UNIX). */
  LINE_SEPARATOR("line.separator"),

  /** User's account name. */
  USER_NAME("user.name"),

  /** User's home directory. */
  USER_HOME("user.home"),

  /** User's current working directory. */
  USER_DIR("user.dir"),

  /**
   * to specify the initial size of ZMalloc's global pool in mega bytes.
   * see more, {@link z.offheap.zmalloc.Allocator#sizeGP}
   */
  ZMALLOC_INITIAL_POOLSIZE("z.offheap.zmalloc.initialPoolSize"),

  /**
   * to specify the threshold number of free pages, to which number the ZMalloc
   * will start to return the unused pages(a.k.a., freePage) to global pool.
   * <p>
   * The default value for this property is
   * {@link z.offheap.zmalloc.Allocator#FREEPAGES_NUM_THRESHOLD_DEFAULT}
   * <p>
   * see more, {@link z.offheap.zmalloc.Allocator#freePagesNumThreshold}
   * and {@link #ZMALLOC_FREEPAGES_NUM_TORETURN}
   */
  ZMALLOC_FREEPAGES_NUM_THRESHOLD("z.offheap.zmalloc.freepages.numThreshold"),

  /**
   * to specify the return number of free pages to global pool when the
   * {@link #ZMALLOC_FREEPAGES_NUM_THRESHOLD} reached.
   * <p>
   * The default value for this property is
   * {@link z.offheap.zmalloc.Allocator#FREEPAGES_NUM_TORETURN_DEFAULT}
   * <p>
   * see more, {@link z.offheap.zmalloc.Allocator#freePagesNumToReturn}
   * and {@link #ZMALLOC_FREEPAGES_NUM_THRESHOLD}
   */
  ZMALLOC_FREEPAGES_NUM_TORETURN("z.offheap.zmalloc.freepages.toReturn"),

  /** not used now */
  ZMALLOC_MAX_POOLSIZE("z.offheap.zmalloc.maxPoolSize");




  private final String key;

  private SystemProperty(String key) {
    this.key = key;
  }

  /**
   * Returns the key used to lookup this system property.
   */
  public String key() {
    return key;
  }

  /**
   * Returns the current value for this system property by delegating to
   * {@link System#getProperty(String)}.
   */
  public String value() {
    return System.getProperty(key);
  }

  /**
   * Sets the system property indicated by the specified key.
   * <p>
   * this method now delegates to {@link System#setProperty(String, String)}.
   * <p>
   *
   * @param      value the value of the system property.
   * @return     the previous value of the system property,
   *             or <code>null</code> if it did not have one.

   */
  public String setValue(String value) {
    return System.setProperty(key, value);
  }

  /**
   * Returns a string representation of this system property.
   */
  @Override public String toString() {
    return key() + "=" + value();
  }
}
