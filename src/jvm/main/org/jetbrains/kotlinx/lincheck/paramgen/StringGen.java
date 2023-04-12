package org.jetbrains.kotlinx.lincheck.paramgen;

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

import org.jetbrains.kotlinx.lincheck.RandomProvider;

import java.util.Random;

public class StringGen implements ParameterGenerator<String> {
    private static final int DEFAULT_MAX_WORD_LENGTH = 15;
    private static final String DEFAULT_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_ ";

    private final Random random;
    private final int maxWordLength;
    private final String alphabet;
    private int currentWordLength = 1;

    public StringGen(RandomProvider randomProvider, String configuration) {
        random = randomProvider.createRandom();
        if (configuration.isEmpty()) { // use default configuration
            maxWordLength = DEFAULT_MAX_WORD_LENGTH;
            alphabet = DEFAULT_ALPHABET;
            return;
        }
        int firstCommaIndex = configuration.indexOf(':');
        if (firstCommaIndex < 0) { // maxWordLength only
            maxWordLength = Integer.parseInt(configuration);
            alphabet = DEFAULT_ALPHABET;
        } else { // maxWordLength:alphabet
            maxWordLength = Integer.parseInt(configuration.substring(0, firstCommaIndex));
            alphabet = configuration.substring(firstCommaIndex + 1);
        }
    }

    public String generate() {
        if (currentWordLength < maxWordLength && random.nextBoolean()) {
            currentWordLength++;
        }

        char[] cs = new char[currentWordLength];
        for (int i = 0; i < cs.length; i++) {
            cs[i] = alphabet.charAt(random.nextInt(currentWordLength));
        }

        return new String(cs);
    }
}
