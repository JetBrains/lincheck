package com.devexperts.dxlab.lincheck;

/*
 * #%L
 * Lincheck
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

import com.devexperts.dxlab.lincheck.annotations.Operation;

/**
 * The instance of this class represents a result of actor invocation.
 * <p> If the actor invocation does not return value the {@link Type#VOID void result type} is used.
 * <p> If the actor invocation returns any value then the {@link Type#VALUE value result type} is used.
 * <p> If the actor invocation fails with the specified in {@link Operation#handleExceptionsAsResult()}
 * the {@link Type#EXCEPTION exception result type} is used.
 */
public class Result {
    private final Type type;
    private final Object value;
    private final Class<? extends Throwable> tClazz;

    private Result(Type type, Object value, Class<? extends Throwable> tClazz) {
        this.type = type;
        this.value = value;
        this.tClazz = tClazz;
    }

    public static Result createVoidResult() {
        return new Result(Type.VOID, null, null);
    }

    public static Result createValueResult(Object value) {
        return new Result(Type.VALUE, value, null);
    }

    public static Result createExceptionResult(Class<? extends Throwable> tClazz) {
        return new Result(Type.EXCEPTION, null, tClazz);
    }

    public Type getType() {
        return type;
    }

    public Object getValue() {
        return value;
    }

    public Class<? extends Throwable> getThrowableClass() {
        return tClazz;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Result result = (Result) o;
        if (type != result.type)
            return false;
        if (value != null ? !value.equals(result.value) : result.value != null)
            return false;
        return tClazz != null ? tClazz.equals(result.tClazz) : result.tClazz == null;
    }

    @Override
    public int hashCode() {
        int result = type != null ? type.hashCode() : 0;
        result = 31 * result + (value != null ? value.hashCode() : 0);
        result = 31 * result + (tClazz != null ? tClazz.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        switch (type) {
        case VOID: return "void";
        case VALUE: return "" + value;
        case EXCEPTION: return "exception=" + tClazz.getName();
        default: throw new IllegalStateException("Unknown result type: " + type);
        }
    }

    public enum Type {
        VALUE, VOID, EXCEPTION
    }
}
