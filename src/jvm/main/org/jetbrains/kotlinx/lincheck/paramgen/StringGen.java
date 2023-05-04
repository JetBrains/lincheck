/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.jetbrains.kotlinx.lincheck.paramgen;

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
            cs[i] = alphabet.charAt(random.nextInt(alphabet.length()));
        }

        return new String(cs);
    }

    @Override
    public void resetRange() {
        currentWordLength = 1;
    }
}
