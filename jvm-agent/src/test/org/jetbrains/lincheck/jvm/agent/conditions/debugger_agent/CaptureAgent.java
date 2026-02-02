// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.lincheck.jvm.agent.conditions.debugger_agent;

import org.objectweb.asm.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.*;

import static org.jetbrains.lincheck.jvm.agent.TransformationUtilsKt.ASM_API;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "CallToPrintStackTrace", "rawtypes"})
public final class CaptureAgent {
  private static Instrumentation ourInstrumentation;
  private static final Set<Class> mySkipped = new HashSet<>();

  private static final Map<String, List<InstrumentPoint>> myInstrumentPoints = new HashMap<>();

  public static void init(Properties properties, Instrumentation instrumentation) {
    ourInstrumentation = instrumentation;
    try {
      applyProperties(properties);

      if (instrumentThrowable()) {
        instrumentation.addTransformer(new ThrowableTransformer(), true);
      }

      // remember already loaded and not instrumented classes to skip them during retransform
      for (Class aClass : instrumentation.getAllLoadedClasses()) {
        if (instrumentThrowable() && ThrowableTransformer.THROWABLE_NAME.equals(getInternalClsName(aClass))) {
          instrumentation.retransformClasses(aClass);
        }
        else {
          List<InstrumentPoint> points = myInstrumentPoints.get(getInternalClsName(aClass));
          if (points != null) {
            for (InstrumentPoint point : points) {
              if (!point.myCapture) {
                mySkipped.add(aClass);
              }
            }
          }
        }
      }

      instrumentation.addTransformer(new CaptureTransformer(), true);

      // Trying to reinstrument java.lang.Thread
      // fails with dcevm, does not work with other vms :(
      //for (Class aClass : instrumentation.getAllLoadedClasses()) {
      //  String name = aClass.getName().replaceAll("\\.", "/");
      //  if (myCapturePoints.containsKey(name) || myInsertPoints.containsKey(name)) {
      //    try {
      //      instrumentation.retransformClasses(aClass);
      //    }
      //    catch (UnmodifiableClassException e) {
      //      e.printStackTrace();
      //    }
      //  }
      //}

      setupJboss();

      if (CaptureStorage.DEBUG) {
        System.out.println("Capture agent: ready");
      }
    }
    catch (Throwable e) {
      System.err.println("Critical error in IDEA Async Stack Traces instrumenting agent. Agent is now disabled. Please report to IDEA support:");
      e.printStackTrace();
    }
  }

  private static void setupJboss() {
    String modulesKey = "jboss.modules.system.pkgs";
    String property = System.getProperty(modulesKey, "");
    if (!property.isEmpty()) {
      property += ",";
    }
    property += "com.intellij.rt";
    System.setProperty(modulesKey, property);
  }

  private static void applyProperties(Properties properties) {
    if (Boolean.parseBoolean(properties.getProperty("disabled", "false"))) {
      CaptureStorage.setEnabled(false);
    }

    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      addPoint((String)entry.getKey(), (String)entry.getValue());
    }
  }

  private static class CaptureTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(ClassLoader loader,
                            String className,
                            Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
      if (className != null && (classBeingRedefined == null || !mySkipped.contains(classBeingRedefined))) {
        List<InstrumentPoint> classPoints = myInstrumentPoints.get(className);
        if (classPoints != null) {
          try {
            ClassTransformer transformer = new ClassTransformer(className, classfileBuffer, ClassWriter.COMPUTE_FRAMES, loader);
            return transformer.accept(new CaptureInstrumentor(ASM_API, transformer.writer, classPoints), 0, true);
          }
          catch (Exception e) {
            System.out.println("Capture agent: failed to instrument " + className);
            e.printStackTrace();
          }
        }
      }
      return null;
    }
  }

  @SuppressWarnings("TryFinallyCanBeTryWithResources")
  static void storeClassForDebug(String className, byte[] bytes) {
    if (CaptureStorage.DEBUG) {
      try {
        FileOutputStream stream = new FileOutputStream("instrumented_" + className.replaceAll("/", "_") + ".class");
        try {
          stream.write(bytes);
        }
        finally {
          stream.close();
        }
      }
      catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  private static class CaptureInstrumentor extends ClassVisitor {
    private final List<? extends InstrumentPoint> myInstrumentPoints;
    private final Map<String, String> myFields = new HashMap<>();
    private String mySuperName;
    private boolean myIsInterface;

    CaptureInstrumentor(int api, ClassVisitor cv, List<? extends InstrumentPoint> instrumentPoints) {
      super(api, cv);
      this.myInstrumentPoints = instrumentPoints;
    }

    private static String getNewName(String name) {
      return name + CaptureStorage.GENERATED_INSERT_METHOD_POSTFIX;
    }

    private static String getMethodDisplayName(String className, String methodName, String desc) {
      return className + "." + methodName + desc;
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
      mySuperName = superName;
      myIsInterface = (access & Opcodes.ACC_INTERFACE) != 0;
      super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
      myFields.put(name, desc);
      return super.visitField(access, name, desc, signature, value);
    }

    @Override
    public MethodVisitor visitMethod(final int access, String name, final String desc, String signature, String[] exceptions) {
      if ((access & Opcodes.ACC_BRIDGE) == 0) {
        for (final InstrumentPoint point : myInstrumentPoints) {
          if (point.matchesMethod(name, desc)) {
            final String methodDisplayName = getMethodDisplayName(point.myClassName, name, desc);
            if (CaptureStorage.DEBUG) {
              System.out.println(
                "Capture agent: instrumented " + (point.myCapture ? "capture" : "insert") + " point at " + methodDisplayName);
            }
            if (point.myCapture) { // capture
              // for constructors and "this" key - move capture to after the super constructor call
              if (CONSTRUCTOR.equals(name) && point.myKeyProvider == THIS_KEY_PROVIDER) {
                return new MethodVisitor(api, super.visitMethod(access, name, desc, signature, exceptions)) {
                  boolean captured = false;

                  @Override
                  public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                    super.visitMethodInsn(opcode, owner, name, desc, itf);
                    if (opcode == Opcodes.INVOKESPECIAL &&
                        !captured &&
                        owner.equals(mySuperName) &&
                        name.equals(CONSTRUCTOR)) { // super constructor
                      capture(mv, point.myKeyProvider, (access & Opcodes.ACC_STATIC) != 0,
                              Type.getMethodType(desc).getArgumentTypes(), methodDisplayName);
                      captured = true;
                    }
                  }
                };
              }
              else {
                return new MethodVisitor(api, super.visitMethod(access, name, desc, signature, exceptions)) {
                  @Override
                  public void visitCode() {
                    capture(mv, point.myKeyProvider, (access & Opcodes.ACC_STATIC) != 0, Type.getMethodType(desc).getArgumentTypes(),
                            methodDisplayName);
                    super.visitCode();
                  }
                };
              }
            }
            else { // insert
              if (CONSTRUCTOR.equals(name)) {
                throw new IllegalStateException("Unable to create insert point at " + methodDisplayName +". Constructors are not yet supported.");
              }
              generateWrapper(access, name, desc, signature, exceptions, point, methodDisplayName);
              return super.visitMethod(access, getNewName(name), desc, signature, exceptions);
            }
          }
        }
      }
      return super.visitMethod(access, name, desc, signature, exceptions);
    }

    private void generateWrapper(int access,
                                 String name,
                                 String desc,
                                 String signature,
                                 String[] exceptions,
                                 InstrumentPoint insertPoint,
                                 String methodDisplayName) {
      MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);

      Label start = new Label();
      mv.visitLabel(start);

      boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
      Type[] argumentTypes = Type.getMethodType(desc).getArgumentTypes();

      insertEnter(mv, insertPoint.myKeyProvider, isStatic, argumentTypes, methodDisplayName);

      // this
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      // params
      int index = isStatic ? 0 : 1;
      for (Type t : argumentTypes) {
        mv.visitVarInsn(t.getOpcode(Opcodes.ILOAD), index);
        index += t.getSize();
      }
      // original call
      mv.visitMethodInsn(isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL,
                         insertPoint.myClassName, getNewName(insertPoint.myMethodName), desc, myIsInterface);

      Label end = new Label();
      mv.visitLabel(end);

      // regular exit
      insertExit(mv, insertPoint.myKeyProvider, isStatic, argumentTypes, methodDisplayName);
      mv.visitInsn(Type.getReturnType(desc).getOpcode(Opcodes.IRETURN));

      Label catchLabel = new Label();
      mv.visitLabel(catchLabel);
      mv.visitTryCatchBlock(start, end, catchLabel, null);

      // exception exit
      insertExit(mv, insertPoint.myKeyProvider, isStatic, argumentTypes, methodDisplayName);
      mv.visitInsn(Opcodes.ATHROW);

      mv.visitMaxs(0, 0);
      mv.visitEnd();
    }

    private void capture(MethodVisitor mv,
                         KeyProvider keyProvider,
                         boolean isStatic,
                         Type[] argumentTypes,
                         String methodDisplayName) {
      storageCall(mv, keyProvider, isStatic, argumentTypes, "capture", methodDisplayName);
    }

    private void insertEnter(MethodVisitor mv,
                             KeyProvider keyProvider,
                             boolean isStatic,
                             Type[] argumentTypes,
                             String methodDisplayName) {
      storageCall(mv, keyProvider, isStatic, argumentTypes, "insertEnter", methodDisplayName);
    }

    private void insertExit(MethodVisitor mv,
                            KeyProvider keyProvider,
                            boolean isStatic,
                            Type[] argumentTypes,
                            String methodDisplayName) {
      storageCall(mv, keyProvider, isStatic, argumentTypes, "insertExit", methodDisplayName);
    }

    private void storageCall(MethodVisitor mv,
                             KeyProvider keyProvider,
                             boolean isStatic,
                             Type[] argumentTypes,
                             String storageMethodName,
                             String methodDisplayName) {
      keyProvider.loadKey(mv, isStatic, argumentTypes, methodDisplayName, this);
      invokeStorageMethod(mv, storageMethodName);
    }
  }

  static void invokeStorageMethod(MethodVisitor mv, String name) {
    List<Method> applicable = new ArrayList<>();
    for (Method m : CaptureStorage.class.getMethods()) {
      if (name.equals(m.getName())) {
        applicable.add(m);
      }
    }
    if (applicable.isEmpty()) {
      throw new IllegalStateException("Unable to find Storage method " + name);
    }
    if (applicable.size() > 1) {
      throw new IllegalStateException("More than one Storage method with the name " + name);
    }
    Method method = applicable.get(0);
    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            getInternalClsName(CaptureStorage.class),
            name,
            Type.getMethodDescriptor(method),
            false);
  }

  private static class InstrumentPoint {
    final static String ANY_DESC = "*";

    final boolean myCapture;
    final String myClassName;
    final String myMethodName;
    final String myMethodDesc;
    final KeyProvider myKeyProvider;

    InstrumentPoint(boolean capture, String className, String methodName, String methodDesc, KeyProvider keyProvider) {
      myCapture = capture;
      myClassName = className;
      myMethodName = methodName;
      myMethodDesc = methodDesc;
      myKeyProvider = keyProvider;
    }

    boolean matchesMethod(String name, String desc) {
      if (!myMethodName.equals(name)) {
        return false;
      }
      return myMethodDesc.equals(ANY_DESC) || myMethodDesc.equals(desc);
    }
  }

  // to be run from the debugger
  @SuppressWarnings("unused")
  public static void addCapturePoints(String capturePoints) throws UnmodifiableClassException, IOException {
    if (CaptureStorage.DEBUG) {
      System.out.println("Capture agent: adding points " + capturePoints);
    }

    Properties properties = new Properties();
    properties.load(new StringReader(capturePoints));

    Set<String> classNames = new HashSet<>();

    for (Map.Entry<Object, Object> entry : properties.entrySet()) {
      InstrumentPoint point = addPoint((String)entry.getKey(), (String)entry.getValue());
      if (point != null) {
        classNames.add(getClassName(point.myClassName));
      }
    }

    List<Class<?>> classes = new ArrayList<>(classNames.size());
    for (Class<?> aClass : ourInstrumentation.getAllLoadedClasses()) {
      if (classNames.contains(aClass.getName())) {
        classes.add(aClass);
      }
    }

    if (!classes.isEmpty()) {
      if (CaptureStorage.DEBUG) {
        System.out.println("Capture agent: retransforming " + classes);
      }

      ourInstrumentation.retransformClasses(classes.toArray(new Class[0]));
    }
  }

  private static InstrumentPoint addPoint(String propertyKey, String propertyValue) {
    if (propertyKey.startsWith("capture")) {
      return addPoint(true, propertyValue);
    }
    else if (propertyKey.startsWith("insert")) {
      return addPoint(false, propertyValue);
    }
    return null;
  }

  private static InstrumentPoint addPoint(boolean capture, String line) {
    String[] split = line.split(" ");
    KeyProvider keyProvider = createKeyProvider(Arrays.copyOfRange(split, 3, split.length));
    return addCapturePoint(capture, split[0], split[1], split[2], keyProvider);
  }

  private static InstrumentPoint addCapturePoint(boolean capture,
                                                 String className,
                                                 String methodName,
                                                 String methodDesc,
                                                 KeyProvider keyProvider) {
    List<InstrumentPoint> points = myInstrumentPoints.get(className);
    if (points == null) {
      points = new ArrayList<>(1);
      myInstrumentPoints.put(className, points);
    }
    InstrumentPoint point = new InstrumentPoint(capture, className, methodName, methodDesc, keyProvider);
    points.add(point);
    return point;
  }

  private static final KeyProvider FIRST_PARAM = param(0);

  static final KeyProvider THIS_KEY_PROVIDER = new KeyProvider() {
    @Override
    public void loadKey(MethodVisitor mv,
                        boolean isStatic,
                        Type[] argumentTypes,
                        String methodDisplayName,
                        CaptureInstrumentor instrumentor) {
      if (isStatic) {
        throw new IllegalStateException("This is not available in a static method " + methodDisplayName);
      }
      mv.visitVarInsn(Opcodes.ALOAD, 0);
    }
  };

  private static KeyProvider createKeyProvider(String[] line) {
    if ("this".equals(line[0])) {
      return THIS_KEY_PROVIDER;
    }
    if (isNumber(line[0])) {
      try {
        return new ParamKeyProvider(Integer.parseInt(line[0]));
      }
      catch (NumberFormatException ignored) {
      }
    }
    return new FieldKeyProvider(line[0], line[1]);
  }

  private static boolean isNumber(String s) {
    if (s == null) return false;
    for (int i = 0; i < s.length(); ++i) {
      if (!Character.isDigit(s.charAt(i))) return false;
    }
    return true;
  }

  private interface KeyProvider {
    void loadKey(MethodVisitor mv, boolean isStatic, Type[] argumentTypes, String methodDisplayName, CaptureInstrumentor instrumentor);
  }

  private static class FieldKeyProvider implements KeyProvider {
    private final String myClassName;
    private final String myFieldName;

    FieldKeyProvider(String className, String fieldName) {
      myClassName = className;
      myFieldName = fieldName;
    }

    @Override
    public void loadKey(MethodVisitor mv,
                        boolean isStatic,
                        Type[] argumentTypes,
                        String methodDisplayName,
                        CaptureInstrumentor instrumentor) {
      String desc = instrumentor.myFields.get(myFieldName);
      if (desc == null) {
        throw new IllegalStateException("Field " + myFieldName + " was not found");
      }
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      mv.visitFieldInsn(Opcodes.GETFIELD, myClassName, myFieldName, desc);
    }
  }

  private static class CoroutineOwnerKeyProvider implements KeyProvider {
    @Override
    public void loadKey(MethodVisitor mv,
                        boolean isStatic,
                        Type[] argumentTypes,
                        String methodDisplayName,
                        CaptureInstrumentor instrumentor) {
      mv.visitVarInsn(Opcodes.ALOAD, 0);
      invokeStorageMethod(mv, "coroutineOwner");
    }
  }

  private static class ParamKeyProvider implements KeyProvider {
    private final int myIdx;

    ParamKeyProvider(int idx) {
      myIdx = idx;
    }

    @Override
    public void loadKey(MethodVisitor mv,
                        boolean isStatic,
                        Type[] argumentTypes,
                        String methodDisplayName,
                        CaptureInstrumentor instrumentor) {
      int index = isStatic ? 0 : 1;
      if (myIdx >= argumentTypes.length) {
        throw new IllegalStateException(
          "Argument with id " + myIdx + " is not available, method " + methodDisplayName + " has only " + argumentTypes.length);
      }
      int sort = argumentTypes[myIdx].getSort();
      if (sort != Type.OBJECT && sort != Type.ARRAY) {
        throw new IllegalStateException(
          "Argument with id " + myIdx + " in method " + methodDisplayName + " must be an object");
      }
      for (int i = 0; i < myIdx; i++) {
        index += argumentTypes[i].getSize();
      }
      mv.visitVarInsn(Opcodes.ALOAD, index);
    }
  }

  private static void addCapture(String className, String methodName, KeyProvider key) {
    addCapturePoint(true, className, methodName, InstrumentPoint.ANY_DESC, key);
  }

  private static void addInsert(String className, String methodName, KeyProvider key) {
    addCapturePoint(false, className, methodName, InstrumentPoint.ANY_DESC, key);
  }

  private static KeyProvider param(int idx) {
    return new ParamKeyProvider(idx);
  }

  public static final String CONSTRUCTOR = "<init>";

  // predefined points
  static {
    addCapture("java/awt/event/InvocationEvent", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("java/awt/event/InvocationEvent", "dispatch", THIS_KEY_PROVIDER);

    addCapture("java/lang/Thread", "start", THIS_KEY_PROVIDER);
    addInsert("java/lang/Thread", "run", THIS_KEY_PROVIDER);

    addCapture("java/util/concurrent/FutureTask", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("java/util/concurrent/FutureTask", "run", THIS_KEY_PROVIDER);
    addInsert("java/util/concurrent/FutureTask", "runAndReset", THIS_KEY_PROVIDER);

    addCapture("java/util/concurrent/CompletableFuture$AsyncSupply", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("java/util/concurrent/CompletableFuture$AsyncSupply", "run", THIS_KEY_PROVIDER);

    addCapture("java/util/concurrent/CompletableFuture$AsyncRun", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("java/util/concurrent/CompletableFuture$AsyncRun", "run", THIS_KEY_PROVIDER);

    // CompletableFuture instrumentation:
    // handle every Completion subclass that invokes some callback (and not another future).
    for (String completionClass : new String[]{
            // Last updated for JDK 23.
            "UniApply", "UniAccept", "UniRun",
            "UniWhenComplete", "UniHandle", "UniExceptionally",
            "UniComposeExceptionally", "UniCompose",
            "BiApply", "BiAccept", "BiRun",
            "OrApply", "OrAccept", "OrRun",
    }) {
      addCapture("java/util/concurrent/CompletableFuture$" + completionClass, CONSTRUCTOR, THIS_KEY_PROVIDER);
      addInsert("java/util/concurrent/CompletableFuture$" + completionClass, "tryFire", THIS_KEY_PROVIDER);
    }

    addCapture("java/util/concurrent/ForkJoinTask", "fork", THIS_KEY_PROVIDER);
    addInsert("java/util/concurrent/ForkJoinTask", "doExec", THIS_KEY_PROVIDER);

    // netty
    addCapture("io/netty/util/concurrent/SingleThreadEventExecutor", "addTask", FIRST_PARAM);
    addInsert("io/netty/util/concurrent/AbstractEventExecutor", "safeExecute", FIRST_PARAM);

    // scala
    addCapture("scala/concurrent/impl/Future$PromiseCompletingRunnable", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("scala/concurrent/impl/Future$PromiseCompletingRunnable", "run", THIS_KEY_PROVIDER);

    addCapture("scala/concurrent/impl/CallbackRunnable", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("scala/concurrent/impl/CallbackRunnable", "run", THIS_KEY_PROVIDER);

    addCapture("scala/concurrent/impl/Promise$Transformation", CONSTRUCTOR, THIS_KEY_PROVIDER);
    addInsert("scala/concurrent/impl/Promise$Transformation", "run", THIS_KEY_PROVIDER);

    // akka-scala
    addCapture("akka/actor/ScalaActorRef", "$bang", FIRST_PARAM);
    addCapture("akka/actor/RepointableActorRef", "$bang", FIRST_PARAM);
    addCapture("akka/actor/LocalActorRef", "$bang", FIRST_PARAM);
    addCapture("akka/actor/ActorRef", "$bang", FIRST_PARAM);
    addInsert("akka/actor/Actor$class", "aroundReceive", param(2));
    addInsert("akka/actor/ActorCell", "receiveMessage", FIRST_PARAM);

    // JavaFX
    addCapture("com/sun/glass/ui/InvokeLaterDispatcher", "invokeLater", FIRST_PARAM);
    addInsert("com/sun/glass/ui/InvokeLaterDispatcher$Future", "run",
              new FieldKeyProvider("com/sun/glass/ui/InvokeLaterDispatcher$Future", "runnable"));

    if (Boolean.getBoolean("debugger.agent.enable.coroutines")) {
      if (!Boolean.getBoolean("kotlinx.coroutines.debug.enable.creation.stack.trace")) {
        // Kotlin coroutines
        addCapture("kotlinx/coroutines/debug/internal/DebugProbesImpl$CoroutineOwner", CONSTRUCTOR, THIS_KEY_PROVIDER);
        addInsert("kotlin/coroutines/jvm/internal/BaseContinuationImpl", "resumeWith", new CoroutineOwnerKeyProvider());
      }
      if (Boolean.getBoolean("kotlinx.coroutines.debug.enable.flows.stack.trace")) {
        // Flows
        addCapture("kotlinx/coroutines/flow/internal/FlowValueWrapperInternal", CONSTRUCTOR, THIS_KEY_PROVIDER);

        addInsert("kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "emitInternal", param(1));
        addInsert("kotlinx/coroutines/flow/internal/FlowValueWrapperInternalKt", "debuggerCapture", FIRST_PARAM);
      }
    }
  }

  static String getInternalClsName(String typeDescriptor) {
    return Type.getType(typeDescriptor).getInternalName();
  }

  static String getInternalClsName(Class<?> cls) {
    return Type.getInternalName(cls);
  }

  static String getClassName(String internalClsName) {
    return Type.getObjectType(internalClsName).getClassName();
  }

  private static boolean instrumentThrowable() {
    return Boolean.parseBoolean(System.getProperty("debugger.agent.support.throwable", "true"));
  }

  static int throwableAsyncStackDepthLimit() {
    return Integer.getInteger("debugger.agent.throwable.async.stack.depth", 1024);
  }
}
