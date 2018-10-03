package romix.scala;

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
 * Mimic Option in Scala
 *  
 * @author Roman Levenstein <romixlev@gmail.com>
 *
 * @param <V>
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class Option<V> {
    static None none = new None();
    public static <V> Option<V> makeOption(V o){
        if(o!=null)
            return new Some<V>(o);
        else
            return (Option<V>)none;
    }

    public static <V> Option<V> makeOption(){
        return (Option<V>)none;
    }
    public boolean nonEmpty () {
        return false;
    }
}
