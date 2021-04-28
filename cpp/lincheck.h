#pragma once

#include <random>
#include <stdlib.h>
#include <experimental/type_traits>
#include "../build/bin/native/debugShared/libnative_api.h"

extern libnative_ExportedSymbols *libnative_symbols(void);

namespace Lincheck {
    template<typename type>
    struct to_string {
        std::string operator()(const type &val) const noexcept {
            return std::to_string(val);
        }
    };

    template<typename type>
    struct hash {
        std::size_t operator()(const type &val) const noexcept {
            return std::hash<type>()(val);
        }
    };

    template<typename type>
    class ParameterGenerator {
        std::mt19937 rnd = std::mt19937(rand());
    public:
        type generate() {
            return rnd() % 14 - 7;
        }
    };

    template<typename TestClass, typename SequentialSpecification>
    class LincheckConfiguration {
        libnative_ExportedSymbols *lib = libnative_symbols();
        libnative_kref_org_jetbrains_kotlinx_lincheck_NativeAPIStressConfiguration configuration = lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.NativeAPIStressConfiguration();

        typedef void *(*constructor_pointer)();

        typedef void (*destructor_pointer)(void *);

        typedef bool (*equals_pointer)(void *, void *);

        typedef int (*hashCode_pointer)(void *);

    public:
        LincheckConfiguration() {

            constructor_pointer *instance_constructor = new constructor_pointer();
            *instance_constructor = []() -> void * { return new TestClass(); };

            destructor_pointer *instance_destructor = new destructor_pointer();
            *instance_destructor = [](void *p) { delete (TestClass *) p; };

            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInitialState(
                    configuration,
                    (void *) *instance_constructor, // constructor
                    (void *) *instance_destructor // destructor
            );

            constructor_pointer *constructor = new constructor_pointer();
            *constructor = []() -> void * { return new SequentialSpecification(); };

            destructor_pointer *destructor = new destructor_pointer();
            *destructor = [](void *p) { delete (SequentialSpecification *) p; };

            equals_pointer *equals = new equals_pointer();
            *equals = [](void *a, void *b) -> bool { return *(SequentialSpecification *) a == *(SequentialSpecification *) b; };

            hashCode_pointer *hashCode = new hashCode_pointer();
            *hashCode = [](void *instance) -> int {
                return Lincheck::hash<SequentialSpecification>()(*(SequentialSpecification *) instance);
            };

            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupSequentialSpecification(
                    configuration,
                    (void *) *constructor, // constructor
                    (void *) *destructor, // destructor
                    (void *) *equals, // equals
                    (void *) *hashCode // hashCode
            );
        }

        void iterations(int count) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupIterations(
                    configuration, count);
        }

        void invocationsPerIteration(int count) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInvocationsPerIteration(
                    configuration, count);
        }

        void minimizeFailedScenario(bool minimizeFailedScenario) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupMinimizeFailedScenario(
                    configuration, minimizeFailedScenario);
        }

        template<void (*f)()>
        void initThreadFunction() {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupInitThreadFunction(
                    configuration, (void *) (void (*)()) []() {f();});
        }

        template<void (*f)()>
        void finishThreadFunction() {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupFinishThreadFunction(
                    configuration, (void *) (void (*)()) []() {f();});
        }

        template<typename Ret, Ret (TestClass::*op)(), Ret (SequentialSpecification::*seq_spec)()>
        void operation(const char *operationName, bool useOnce = false) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperation1(
                    configuration,
                    (void *) (void *(*)(void *)) [](void *instance) -> void * { // operation
                        auto *obj = (TestClass *) instance; // add type to void*
                        Ret res = (obj->*op)(); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void *(*)(void *)) [](void *instance) -> void * { // sequential specification
                        auto *obj = (SequentialSpecification *) instance; // add type to void*
                        Ret res = (obj->*seq_spec)(); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void (*)(void *)) [](void *ret) { // Ret destructor
                        Ret *obj = (Ret *) ret; // add type to void*
                        delete obj; // destructor
                    },
                    (void *) (bool (*)(void *, void *)) [](void *a, void *b) -> bool { // Ret equals
                        Ret *obj_a = (Ret *) a; // add type to void*
                        Ret *obj_b = (Ret *) b; // add type to void*
                        return *obj_a == *obj_b;
                    },
                    (void *) (int (*)(void *)) [](void *ret) -> int { // Ret hashCode
                        return Lincheck::hash<Ret>()(*(Ret *) ret);
                    },
                    (void *) (void (*)(void *, char *, int)) [](void *ret, char *dest, int destSize) { // Ret toString
                        strncpy(dest, Lincheck::to_string<Ret>()(*(Ret *) ret).c_str(), destSize);
                    },
                    operationName,
                    useOnce
            );
        }

        template<typename Ret, typename Arg1, Ret (TestClass::*op)(Arg1), Ret (SequentialSpecification::*seq_spec)(Arg1)>
        void operation(const char *operationName, bool useOnce = false) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperation2(
                    configuration,
                    (void *) (void *(*)()) []() -> void * { // arg1_gen_initial_state
                        return new ParameterGenerator<Arg1>();
                    },
                    (void *) (void *(*)(void *)) [](void *gen) -> void * { // arg1_gen_generate
                        auto *obj = (ParameterGenerator<Arg1> *) gen; // add type to void*
                        Arg1 arg = obj->generate(); // invoke generate method
                        return new Arg1(arg); // copy from stack to heap and return
                    },

                    (void *) (void (*)(void *, char *, int)) [](void *arg, char *dest, int destSize) { // arg1_toString
                        strncpy(dest, Lincheck::to_string<Arg1>()(*(Arg1 *) arg).c_str(), destSize);
                    },
                    (void *) (void *(*)(void *, void *)) [](void *instance, void *arg1) -> void * { // operation
                        auto *obj = (TestClass *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        Ret res = (obj->*op)(*a1); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void *(*)(void *, void *)) [](void *instance,
                                                            void *arg1) -> void * { // sequential specification
                        auto *obj = (SequentialSpecification *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        Ret res = (obj->*seq_spec)(*a1); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void (*)(void *)) [](void *ret) { // Ret destructor
                        Ret *obj = (Ret *) ret; // add type to void*
                        delete obj; // destructor
                    },
                    (void *) (bool (*)(void *, void *)) [](void *a, void *b) -> bool { // Ret equals
                        Ret *obj_a = (Ret *) a; // add type to void*
                        Ret *obj_b = (Ret *) b; // add type to void*
                        return *obj_a == *obj_b;
                    },
                    (void *) (int (*)(void *)) [](void *ret) -> int { // Ret hashCode
                        return Lincheck::hash<Ret>()(*(Ret *) ret);
                    },
                    (void *) (void (*)(void *, char *, int)) [](void *ret, char *dest, int destSize) { // Ret toString
                        strncpy(dest, Lincheck::to_string<Ret>()(*(Ret *) ret).c_str(), destSize);
                    },
                    operationName,
                    useOnce
            );
        }


        template<typename Ret, typename Arg1, typename Arg2, Ret (TestClass::*op)(Arg1,
                                                                                  Arg2), Ret (SequentialSpecification::*seq_spec)(
                Arg1, Arg2)>
        void operation(const char *operationName, bool useOnce = false) {
            lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.setupOperation3(
                    configuration,
                    (void *) (void *(*)()) []() -> void * { // arg1_gen_initial_state
                        return new ParameterGenerator<Arg1>();
                    },
                    (void *) (void *(*)(void *)) [](void *gen) -> void * { // arg1_gen_generate
                        auto *obj = (ParameterGenerator<Arg1> *) gen; // add type to void*
                        Arg1 arg = obj->generate(); // invoke generate method
                        return new Arg1(arg); // copy from stack to heap and return
                    },

                    (void *) (void (*)(void *, char *, int)) [](void *arg, char *dest, int destSize) { // arg1_toString
                        strncpy(dest, Lincheck::to_string<Arg1>()(*(Arg1 *) arg).c_str(), destSize);
                    },
                    (void *) (void *(*)()) []() -> void * { // arg2_gen_initial_state
                        return new ParameterGenerator<Arg2>();
                    },
                    (void *) (void *(*)(void *)) [](void *gen) -> void * { // arg2_gen_generate
                        auto *obj = (ParameterGenerator<Arg2> *) gen; // add type to void*
                        Arg2 arg = obj->generate(); // invoke generate method
                        return new Arg2(arg); // copy from stack to heap and return
                    },

                    (void *) (void (*)(void *, char *, int)) [](void *arg, char *dest, int destSize) { // arg2_toString
                        strncpy(dest, Lincheck::to_string<Arg2>()(*(Arg2 *) arg).c_str(), destSize);
                    },
                    (void *) (void *(*)(void *, void *, void *)) [](void *instance, void *arg1,
                                                                    void *arg2) -> void * { // operation
                        auto *obj = (TestClass *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        auto *a2 = (Arg2 *) arg2;
                        Ret res = (obj->*op)(*a1, *a2); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void *(*)(void *, void *, void *)) [](void *instance,
                                                                    void *arg1,
                                                                    void *arg2) -> void * { // sequential specification
                        auto *obj = (SequentialSpecification *) instance; // add type to void*
                        auto *a1 = (Arg1 *) arg1;
                        auto *a2 = (Arg2 *) arg2;
                        Ret res = (obj->*seq_spec)(*a1, *a2); // invoke op method
                        return new Ret(res); // copy from stack to heap and return
                    },
                    (void *) (void (*)(void *)) [](void *ret) { // Ret destructor
                        Ret *obj = (Ret *) ret; // add type to void*
                        delete obj; // destructor
                    },
                    (void *) (bool (*)(void *, void *)) [](void *a, void *b) -> bool { // Ret equals
                        Ret *obj_a = (Ret *) a; // add type to void*
                        Ret *obj_b = (Ret *) b; // add type to void*
                        return *obj_a == *obj_b;
                    },
                    (void *) (int (*)(void *)) [](void *ret) -> int { // Ret hashCode
                        return Lincheck::hash<Ret>()(*(Ret *) ret);
                    },
                    (void *) (void (*)(void *, char *, int)) [](void *ret, char *dest, int destSize) { // Ret toString
                        strncpy(dest, Lincheck::to_string<Ret>()(*(Ret *) ret).c_str(), destSize);
                    },
                    operationName,
                    useOnce
            );
        }

        std::string runTest(bool printErrorToStderr = true) {
            return lib->kotlin.root.org.jetbrains.kotlinx.lincheck.NativeAPIStressConfiguration.runNativeTest(
                    configuration, printErrorToStderr);
        }
    };
}