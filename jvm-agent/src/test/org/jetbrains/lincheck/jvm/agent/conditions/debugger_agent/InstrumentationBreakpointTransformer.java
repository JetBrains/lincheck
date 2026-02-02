package org.jetbrains.lincheck.jvm.agent.conditions.debugger_agent;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.*;

import static org.jetbrains.lincheck.jvm.agent.TransformationUtilsKt.ASM_API;
import static org.jetbrains.lincheck.jvm.agent.conditions.debugger_agent.CaptureAgent.getInternalClsName;

class InstrumentationBpExceptionWrapper extends RuntimeException {
    final int instrumentationId;

    InstrumentationBpExceptionWrapper(Throwable e, int instrumentationId) {
        super(e);
        this.instrumentationId = instrumentationId;
    }
}

// TODO: here, the fragment class (the class that contains the compiled condition/log/...)
class InstrumentationBreakpointInfo {
    final int instrumentationId;
    final int lineNumber;
    final String fragmentClassName;
    final String fragmentEntryMethodName;
    final String methodSignature;
    final List<String> argumentNames;

    InstrumentationBreakpointInfo(int instrumentationId, int lineNumber, String fragmentClassName, String fragmentEntryMethodName, String methodSignature, List<String> argumentNames) {
        this.instrumentationId = instrumentationId;
        this.lineNumber = lineNumber;
        this.fragmentClassName = fragmentClassName;
        this.fragmentEntryMethodName = fragmentEntryMethodName;
        this.methodSignature = methodSignature;
        this.argumentNames = argumentNames;
    }
}


interface ArgumentGetter {
    void generateArgumentLoad(MethodVisitor mv);
}

class InstrumentationBreakpointMappingInfo {
    final InstrumentationBreakpointInfo inputInfo;
    final List<ArgumentGetter> argumentLoadGenerators;

    InstrumentationBreakpointMappingInfo(InstrumentationBreakpointInfo inputInfo, List<ArgumentGetter> argumentLoadGenerators) {
        this.inputInfo = inputInfo;
        this.argumentLoadGenerators = argumentLoadGenerators;
    }
}

public class InstrumentationBreakpointTransformer {
    private static final Set<String> myClassesWithBreakpoints = new LinkedHashSet<>();

    public static void init(Properties properties, Instrumentation instrumentation) {
        applyProperties(properties);
        instrumentation.addTransformer(new BreakpointInstrumentalist(), true);
    }

    private static void applyProperties(Properties properties) {
        for (Map.Entry<Object, Object> entry : properties.entrySet()) {
            addPoint((String)entry.getKey(), (String)entry.getValue());
        }
    }

    private static void addPoint(String propertyKey, String propertyValue) {
        if (propertyKey.startsWith("instrumentation.breakpoint.class")) {
            addBreakpointClass(propertyValue);
        }
    }

    public static void addBreakpointClass(String className) {
        myClassesWithBreakpoints.add(className);
    }

    private static class BreakpointInstrumentalist implements ClassFileTransformer {
        @Override
        public byte[] transform(final ClassLoader loader, final String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!myClassesWithBreakpoints.contains(className)) {
                return null;
            }

            final List<Integer> successIds = new ArrayList<>();

            try {
                // TODO: get info about the injecting breakpoints
                String[][] infoTable = requestInstrumentedInfo(className);
                if (infoTable.length == 0) {
                    return null;
                }

                // TODO: START parse info
                final Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods = new LinkedHashMap<>();
                for (String[] infoArray : infoTable) {
                    List<String> info = Arrays.asList(infoArray);

                    String className2 = info.get(0);
                    if (!className2.equals(className)) {
                        // TODO: ERROR here
                        continue;
                    }
                    String methodName = info.get(1);
                    int lineNumber = Integer.parseInt(info.get(2));
                    String fragmentClassName = info.get(3);
                    String fragmentEntryMethodName = info.get(4);
                    String methodSignature = info.get(5);
                    List<String> argumentNames = info.subList(6, info.size());

                    Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(methodName, methods);
                    if (lineNumbers == null) {
                        lineNumbers = new LinkedHashMap<>();
                        methods.put(methodName, lineNumbers);
                    }

                    int instrumentationId = extractIdFromFragmentClassName(fragmentClassName);
                    lineNumbers.put(lineNumber, new InstrumentationBreakpointInfo(instrumentationId, lineNumber, fragmentClassName, fragmentEntryMethodName, methodSignature, argumentNames));
                }
                // TODO: FINISH parse info

                if (methods.isEmpty()) {
                    return null;
                }

                final ClassNode classNode = new ClassNode();
                new ClassReader(classfileBuffer).accept(classNode, 0);

                ClassTransformer transformer = new ClassTransformer(
                        className, classfileBuffer, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, loader
                );


                // TODO Nikita: must handle when a method is called
                //  as a part of the injected code,
                //  no breakpoint hit should happen
                return transformer.accept(new ClassVisitor(ASM_API, transformer.writer) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor superMethodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                        final Map<Integer, InstrumentationBreakpointInfo> lineNumbers = getLineNumbers(name, methods);
                        if (lineNumbers == null || lineNumbers.isEmpty()) {
                            return superMethodVisitor;
                        }

                        MethodNode m = null;
                        for (MethodNode method : classNode.methods) {
                            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                                m = method;
                            }
                        }
                        if (m == null) {
                            return superMethodVisitor;
                        }

                        final Map<Integer, InstrumentationBreakpointMappingInfo> argumentMapping = collectArgumentMapping(m, lineNumbers);

                        if (argumentMapping.isEmpty()) {
                            return superMethodVisitor;
                        }

                        return new MethodVisitor(api, superMethodVisitor) {

                            @Override
                            public void visitLineNumber(int line, Label start) {
                                InstrumentationBreakpointMappingInfo instrumentationBreakpointInfo = argumentMapping.get(line);
                                if (instrumentationBreakpointInfo != null) {
                                    // Set only one breakpoint per line
                                    argumentMapping.remove(line);
                                    addBpInstrumentation(instrumentationBreakpointInfo);
                                }
                                super.visitLineNumber(line, start);
                            }

                            private void addBpInstrumentation(InstrumentationBreakpointMappingInfo argumentMapping) {
                                String fragmentClassName = argumentMapping.inputInfo.fragmentClassName;
                                String fragmentEntryMethodName = argumentMapping.inputInfo.fragmentEntryMethodName;
                                int instrumentationId = argumentMapping.inputInfo.instrumentationId;

                                successIds.add(instrumentationId);

                                try {
                                    Label startTry = new Label();
                                    Label endTry = new Label();
                                    Label catchBlock = new Label();
                                    Label afterIf = new Label();
                                    Label checkIsDone = new Label();

                                    String theTransformerClassName = getInternalClsName(InstrumentationBreakpointTransformer.class);

                                    // TODO: call the injected fragment (condition)
                                    //  if (!enterBreakpointCheck()) return
                                    //  try {
                                    //    conditionCodeFragment(arg1, arg2, ...) // inside -- breakpointHit(breakpointId: Int)
                                    //  } catch(t: Throwable) {
                                    //    reportIncorrectInstrumentation(t.toString())
                                    //  }
                                    //  checkIsDone()

                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            theTransformerClassName,
                                            "enterBreakpointCheck",
                                            "()Z",
                                            false);

                                    mv.visitJumpInsn(Opcodes.IFNE, checkIsDone);

                                    mv.visitTryCatchBlock(startTry, endTry, catchBlock, "java/lang/Throwable");

                                    mv.visitLabel(startTry);

                                    // TODO: pass the arguments
                                    for (ArgumentGetter argumentLoadGenerator : argumentMapping.argumentLoadGenerators) {
                                        argumentLoadGenerator.generateArgumentLoad(mv);
                                    }

                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            fragmentClassName,
                                            fragmentEntryMethodName,
                                            argumentMapping.inputInfo.methodSignature,
                                            false);
                                    mv.visitLabel(endTry);
                                    mv.visitJumpInsn(Opcodes.GOTO, afterIf);

                                    mv.visitLabel(catchBlock);
                                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                            "java/lang/Throwable",
                                            "toString",
                                            "()Ljava/lang/String;",
                                            false);
                                    if (instrumentationId >= Short.MAX_VALUE) {
                                        throw new IllegalArgumentException("Instrumentation ID exceeds short range: " + instrumentationId);
                                    }
                                    mv.visitIntInsn(Opcodes.SIPUSH, instrumentationId);
                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            theTransformerClassName,
                                            "reportIncorrectInstrumentation",
                                            "(Ljava/lang/String;I)V",
                                            false);

                                    mv.visitLabel(afterIf);

                                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            theTransformerClassName,
                                            "checkIsDone",
                                            "()V",
                                            false);
                                    mv.visitLabel(checkIsDone);
                                } catch (Throwable e) {
                                    throw new InstrumentationBpExceptionWrapper(e, instrumentationId);
                                }
                            }
                        };
                    }
                }, 0, true);
            } catch (Throwable e) {
                // TODO: injection failure catched
                if (e instanceof InstrumentationBpExceptionWrapper) {
                    instrumentationFailed(e.getCause(), ((InstrumentationBpExceptionWrapper)e).instrumentationId);
                } else {
                    instrumentationFailed(e, -1);
                }
            } finally {
                Integer[] integerArray = successIds.toArray(new Integer[0]);

                int[] intArray = new int[integerArray.length];
                for (int i = 0; i < integerArray.length; i++) {
                    intArray[i] = integerArray[i];
                }

                successfullyInstrumented(loader, intArray);
            }
            return null;
        }
    }

    private static Map<Integer, InstrumentationBreakpointMappingInfo> collectArgumentMapping(MethodNode method, Map<Integer, InstrumentationBreakpointInfo> lineNumbers) {
        final Map<Integer, InstrumentationBreakpointMappingInfo> remappingInfo = new HashMap<>();

        Map<Integer, Integer> visitedLineNumbers = new HashMap<>();

        int lastControlInstructionIndex = -1;

        for (int instructionIndex = 0; instructionIndex < method.instructions.size(); instructionIndex++) {
            AbstractInsnNode instruction = method.instructions.get(instructionIndex);

            int opcode = instruction.getOpcode();
            if (instruction instanceof JumpInsnNode ||
                instruction instanceof TableSwitchInsnNode ||
                instruction instanceof LookupSwitchInsnNode ||
                opcode == Opcodes.RET ||
                opcode == Opcodes.ATHROW ||
                (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
            ) {
                lastControlInstructionIndex = instructionIndex;
            }


            if (!(instruction instanceof LineNumberNode)) {
                continue;
            }

            int lineNumber = ((LineNumberNode)instruction).line;
            InstrumentationBreakpointInfo instrumentationBreakpointInfo = lineNumbers.get(lineNumber);
            if (instrumentationBreakpointInfo == null) {
                continue;
            }
            try {
                // skip non-trivial cases for now
                Integer previous = visitedLineNumbers.put(lineNumber, instructionIndex);
                if (previous != null && previous < lastControlInstructionIndex) {
                    impossibleToInstrument("Several instructions marked with the same line " + lineNumber, instrumentationBreakpointInfo.instrumentationId);
                    remappingInfo.remove(lineNumber);
                }
                else {
                    List<ArgumentGetter> argumentLoadGenerators = new ArrayList<>();
                    for (String argumentName : instrumentationBreakpointInfo.argumentNames) {
                        boolean isFound = false;
                        for (LocalVariableNode localVariableNode : method.localVariables) {
                            if (localVariableNode.name.equals(argumentName) &&
                                    method.instructions.indexOf(localVariableNode.start) <= instructionIndex &&
                                    instructionIndex < method.instructions.indexOf(localVariableNode.end)
                            ) {
                                isFound = true;

                                final Type type = Type.getType(localVariableNode.desc);
                                final int localVariableIndex = localVariableNode.index;
                                argumentLoadGenerators.add(new ArgumentGetter() {
                                    @Override
                                    public void generateArgumentLoad(MethodVisitor mv) {
                                        mv.visitVarInsn(type.getOpcode(Opcodes.ILOAD), localVariableIndex);
                                    }
                                });
                                break;
                            }
                        }
                        if (!isFound) {
                            impossibleToInstrument("Argument " + argumentName  + " not found", instrumentationBreakpointInfo.instrumentationId);
                            break;
                        }
                    }

                    if (argumentLoadGenerators.size() == instrumentationBreakpointInfo.argumentNames.size()) {
                        remappingInfo.put(lineNumber, new InstrumentationBreakpointMappingInfo(instrumentationBreakpointInfo, argumentLoadGenerators));
                    }
                }
            } catch (Throwable e) {
                throw new InstrumentationBpExceptionWrapper(e, instrumentationBreakpointInfo.instrumentationId);
            }
        }

        return remappingInfo;
    }

    private static Map<Integer, InstrumentationBreakpointInfo> getLineNumbers(String methodName, Map<String, Map<Integer, InstrumentationBreakpointInfo>> methods) {
        int index = methodName.indexOf("$lambda$");
        if (index > 0) {
            String whereMethodName = methodName.substring(0, index);
            return methods.get(whereMethodName);
        }

        if (methodName.startsWith("lambda$")) {
            String rest = methodName.substring(7);
            int i = rest.indexOf("$");
            if (i > 0) {
                String whereMethodName = rest.substring(0, i);
                return methods.get(whereMethodName);
            }
        }

        return methods.get(methodName);
    }

    private static final ThreadLocal<Integer> myThreadLocal = new ThreadLocal<>();

    /**
     * This method is used from the Debugger Engine side to check
     * that the triggered breakpoint is not met under some evaluation
     */
    @SuppressWarnings("unused")
    public static boolean isUnderBreakpointCheck() {
        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        return previous > 0;
    }

    /**
     * This method is used from instrumented code
     *
     * @return true if the breakpoint condition/logging should be skipped
     */
    @SuppressWarnings("unused")
    public static boolean enterBreakpointCheck() {
        if (!isUnmutedState) {
            return true;
        }

        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        if (previous > 0) {
            return true;
        }
        myThreadLocal.set(previous + 1);

        return false;
    }

    /** This method is used from instrumented code */
    @SuppressWarnings("unused")
    public static void checkIsDone() {
        Integer previous = myThreadLocal.get();
        if (previous == null) {
            previous = 0;
        }
        myThreadLocal.set(previous - 1);
    }

    /**
     * This field is changing by the Debugger Engine side and used inside instrumented code
     * <p>
     * Should not be initialized, so we can set it from Class Prepare Event from the Debugger Engine,
     * and the `init` will not rewrite it
     */
    @SuppressWarnings("unused")
    public static boolean isUnmutedState; // TODO: for "mute breakpoints" feature

    public static void instrumentationFailed(Throwable e, int instrumentationId) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        PrintStream s = new PrintStream(baos);

        s.println(e);

        StackTraceElement[] trace = e.getStackTrace();
        int lastToReportIndex = 0;
        for (int i = 0; i < trace.length; i++) {
            StackTraceElement traceElement = trace[i];
            if (traceElement.getClassName().startsWith("com.intellij.rt.debugger.agent")) {
                lastToReportIndex = i;
            }
        }
        for (int i = 0; i <= lastToReportIndex; i++) {
            StackTraceElement traceElement = trace[i];
            s.println("\tat " + traceElement);
        }

        reportInstrumentationFailed(baos.toString(), instrumentationId);

        s.close();
    }

    // TODO: is later parsed to Array<InstrumentationBreakpointInfo>
    public static String[][] requestInstrumentedInfo(@SuppressWarnings("unused") String className) {
        return new String[0][];
    }

    @SuppressWarnings("unused")
    public static void successfullyInstrumented(ClassLoader loader, int[] instrumentationIds) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void reportInstrumentationFailed(String report, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void reportIncorrectInstrumentation(String report, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    @SuppressWarnings("unused")
    public static void impossibleToInstrument(String message, int instrumentationId) {
        // The report will be on the IDE side by a special breakpoint
    }

    private static int extractIdFromFragmentClassName(String fragmentClassName) {
        // Find the last digit sequence in the class name
        int i = fragmentClassName.length() - 1;
        while (i >= 0 && Character.isDigit(fragmentClassName.charAt(i))) {
            i--;
        }
        if (i < fragmentClassName.length() - 1) {
            return Integer.parseInt(fragmentClassName.substring(i + 1));
        }
        throw new IllegalArgumentException("Fragment class name does not contain a valid instrumentation ID: " + fragmentClassName);
    }
}
