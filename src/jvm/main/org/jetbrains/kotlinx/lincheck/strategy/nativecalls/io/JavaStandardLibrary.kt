/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.strategy.nativecalls.io

import org.jetbrains.kotlinx.lincheck.trace.MethodSignature
import org.jetbrains.kotlinx.lincheck.trace.Types
import org.jetbrains.kotlinx.lincheck.util.and
import org.jetbrains.kotlinx.lincheck.util.getMethods
import org.jetbrains.kotlinx.lincheck.util.isInstance
import org.jetbrains.kotlinx.lincheck.util.isPublic
import org.jetbrains.kotlinx.lincheck.util.isStatic
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Flushable
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.spi.FileSystemProvider
import kotlin.collections.plus

internal object Java {
    private val consumerType = Types.ObjectType("java.util.function.Consumer")
    object Lang {
        object Object {
            val finalizeMethod = MethodSignature("finalize", Types.MethodType(Types.VOID_TYPE))
        }

        object Iterable {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            val methods = getMethods<java.lang.Iterable<*>>()
            val iteratorMethod = MethodSignature("iterator", Types.MethodType(Types.ObjectType("java.util.Iterator")))
            val forEachMethod =
                MethodSignature("forEach", Types.MethodType(Types.VOID_TYPE, consumerType))
            val spliteratorMethod =
                MethodSignature("spliterator", Types.MethodType(Types.ObjectType("java.util.Spliterator")))
        }
    }
    
    object Util {
        object Iterator {
            val forEachRemainingMethod = MethodSignature(
                "forEachRemaining", Types.MethodType(Types.VOID_TYPE, consumerType)
            )
        }
        
        object Spliterator {
            val tryAdvanceMethod = MethodSignature(
                "tryAdvance", Types.MethodType(Types.BOOLEAN_TYPE, consumerType)
            )
            val forEachRemainingMethod = Iterator.forEachRemainingMethod
        }
    }

    object Io {
        val closeableMethods = getMethods<Closeable>()
        private val flushableMethods = getMethods<Flushable>()
        
        object FileCleanable {
            val clazz: Class<*>? = runCatching { Class.forName("java.io.FileCleanable") }.getOrNull()
            val methods = clazz?.run { getMethods { true } /* <- Extension */ + getMethods() /* <- Java method */ }
        }

        object FileSystem {
            val clazz: Class<*> = Class.forName("java.io.FileSystem")
            val methods = clazz.getMethods(isPublic) + closeableMethods
        }

        object Stream {
            private val BYTE_ARRAY_TYPE = Types.ArrayType(Types.BYTE_TYPE)

            object Input {
                val methods = getMethods<InputStream>(isPublic and isInstance) + closeableMethods

                val readToByteArrayMethod = MethodSignature("read", Types.MethodType(Types.INT_TYPE, BYTE_ARRAY_TYPE))
                val readToByteArrayWithOffsetMethod = MethodSignature(
                    "read", Types.MethodType(Types.INT_TYPE, BYTE_ARRAY_TYPE, Types.INT_TYPE, Types.INT_TYPE)
                )
                val readNBytesToByteArrayWithOffsetMethod = MethodSignature(
                    "readNBytes", Types.MethodType(Types.INT_TYPE, BYTE_ARRAY_TYPE, Types.INT_TYPE, Types.INT_TYPE)
                )
                val transferToMethod = MethodSignature(
                    "transferTo", Types.MethodType(Types.LONG_TYPE, Types.ObjectType("java.io.OutputStream"))
                )

                object File {
                    private val openMethod =
                        MethodSignature("open", Types.MethodType(Types.VOID_TYPE, Types.ObjectType("java.lang.String")))
                    val methods =
                        Input.methods + getMethods<FileInputStream>(isPublic and isInstance) + Lang.Object.finalizeMethod + openMethod
                    val getFileChannelMethod =
                        MethodSignature(
                            "getChannel",
                            Types.MethodType(Types.ObjectType("java.nio.channels.FileChannel"))
                        )
                }
            }

            object Output {
                val methods = getMethods<OutputStream>(isPublic and isInstance) + closeableMethods + flushableMethods
                val writeMethod = MethodSignature("write", Types.MethodType(Types.VOID_TYPE, BYTE_ARRAY_TYPE))
                val writeWithOffsetMethod =
                    MethodSignature(
                        "write",
                        Types.MethodType(Types.VOID_TYPE, BYTE_ARRAY_TYPE, Types.INT_TYPE, Types.INT_TYPE)
                    )

                object File {
                    private val openMethod = MethodSignature(
                        "open",
                        Types.MethodType(Types.VOID_TYPE, Types.ObjectType("java.lang.String"), Types.BOOLEAN_TYPE)
                    )
                    val methods =
                        Output.methods + getMethods<FileOutputStream>(isPublic and isInstance) + Lang.Object.finalizeMethod + openMethod
                    val getFileChannelMethod = Input.File.getFileChannelMethod
                }
            }
        }
    }

    object Nio {
        object File {
            object FileSystem {
                val methods = getMethods<java.nio.file.FileSystem>(isPublic and isInstance) + Io.closeableMethods
                val getRootDirectoriesMethod =
                    MethodSignature("getRootDirectories", Types.MethodType(Types.ObjectType("java.lang.Iterable")))
                val getFileStoresMethod =
                    MethodSignature("getFileStores", Types.MethodType(Types.ObjectType("java.lang.Iterable")))

                object Provider {
                    val methods = getMethods<FileSystemProvider>(isPublic) + Class.forName("sun.nio.fs.AbstractFileSystemProvider").getMethods(
                        isPublic and isInstance
                    )
                    val directoryStreamMethod = methods.single { it.name == "newDirectoryStream" }
                }
            }

            object FileSystems {
                val methods = getMethods<java.nio.file.FileSystems>(isPublic and isStatic)
                val type = Types.ObjectType("java.nio.file.FileSystems")
            }

            object Watchable {
                val methods = getMethods<java.nio.file.Watchable>(isInstance)
            }

            object Path {
                val methods =
                    getMethods<java.nio.file.Path>(isInstance) + Lang.Iterable.methods + Watchable.methods
            }

            object DirectoryStream {
                val methods =
                    getMethods<java.nio.file.DirectoryStream<*>>(isPublic and isInstance) + Lang.Iterable.methods + Io.closeableMethods
            }

            object AttributeView {
                val methods = getMethods<java.nio.file.attribute.AttributeView>(isPublic and isInstance)
            }

            object BasicFileAttributes {
                val methods = getMethods<java.nio.file.attribute.BasicFileAttributes>(isPublic and isInstance)
            }

            object FileStore {
                val methods = getMethods<java.nio.file.FileStore>(isPublic and isInstance)
            }

            object UserPrincipalLookupService {
                val methods = getMethods<java.nio.file.attribute.UserPrincipalLookupService>(isPublic and isInstance)
            }

            object Principal {
                val methods = getMethods<java.security.Principal>(isPublic and isInstance)
            }

            object WatchService {
                val methods = getMethods<java.nio.file.WatchService>(isPublic and isInstance)
            }

            object WatchKey {
                val methods = getMethods<java.nio.file.WatchKey>(isPublic and isInstance)
            }

            object WatchEvent {
                val methods = getMethods<java.nio.file.WatchEvent<*>>(isPublic and isInstance)
            }

            object WatchEventKind {
                val methods = getMethods<java.nio.file.WatchEvent.Kind<*>>(isPublic and isInstance)
            }
        }

        object Channels {
            fun notSupportedYet(): Nothing = error("Nio.Channels are not supported yet")
        }
    }
}
