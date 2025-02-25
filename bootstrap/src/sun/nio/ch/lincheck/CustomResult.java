/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2025 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package sun.nio.ch.lincheck;

public class CustomResult {

    final static class Success extends CustomResult {
        private final Object result;

        public Success(Object result) {
            this.result = result;
        }

        public Object getResult() {
            return result;
        }

        @Override
        public String toString() {
            return "Success(result=" + result + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Success success = (Success) o;
            return result != null ? result.equals(success.result) : success.result == null;
        }

        @Override
        public int hashCode() {
            return result != null ? result.hashCode() : 0;
        }
    }

    final static class Failure extends CustomResult {
        private final Throwable throwable;

        public Failure(Throwable throwable) {
            this.throwable = throwable;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        @Override
        public String toString() {
            return "Failure(throwable=" + throwable + ")";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Failure failure = (Failure) o;
            return throwable != null ? throwable.equals(failure.throwable) : failure.throwable == null;
        }

        @Override
        public int hashCode() {
            return throwable != null ? throwable.hashCode() : 0;
        }
    }
}
