/*
 *  Lincheck - Linearizability checker
 *  Copyright (C) 2015 Devexperts LLC
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.devexperts.dxlab.lincheck.util;

import sun.misc.Contended;

@Contended
public class Result {
    public ResultType resType;
    Object value;
    Class exceptionClass;
    String exceptionName;

    public Result() {
        resType = ResultType.UNDEFINED;
    }

    public void setValue(Object value) {
        resType = ResultType.VALUE;
        this.value = value;
    }

    public void setVoid() {
        resType = ResultType.VOID;
    }

    public void setException(Exception e) {
        resType = ResultType.EXCEPTION;
        exceptionClass = e.getClass();
        exceptionName = e.getMessage();
    }

    public void setUndefined() {
        resType = ResultType.UNDEFINED;
    }

    public void setTimeout() {
        resType = ResultType.TIMEOUT;
    }

    @Override
    public String toString() {
        if (resType == ResultType.EXCEPTION) {
            return exceptionClass.getSimpleName() +
                    (exceptionName == null ? "" : "(" + exceptionName + ")");
        }
        if (resType == ResultType.VOID) {
            return "-10";
        }
        if (value == null) return "-1";
        return value.toString();

//        if (resType == ResultType.EXCEPTION) {
//            return "{" +
//                    resType + " : " +
//                    exceptionClass.getSimpleName() +
//                    (exceptionName == null ? "" : "(" + exceptionName + ")") +
//                    "}";
//        }
//        if (resType == ResultType.VOID) {
//            return "{ VOID }";
//        }
//        return "{" + resType + " : " + value + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Result result = (Result) o;

        if (resType != result.resType) return false;

        if (resType == ResultType.VOID) {
            return true;
        }

        if (resType == ResultType.UNDEFINED) {
            return true;
        }

        if (resType == ResultType.TIMEOUT) {
            return true;
        }


        if (resType == ResultType.VALUE) {
            return (value == null ? result.value == null : value.equals(result.value));
        }

        if (resType == ResultType.EXCEPTION) {
            return (exceptionClass == null ? result.exceptionClass == null : exceptionClass.equals(result.exceptionClass));
        }

        return false;
    }

    @Override
    public int hashCode() {
        int result = resType.hashCode();
        result = 31 * result + (value != null ? value.hashCode() : 0);
        result = 31 * result + (exceptionClass != null ? exceptionClass.hashCode() : 0);
        return result;
    }
}

enum ResultType {
    VALUE, VOID, EXCEPTION, TIMEOUT, UNDEFINED
}
