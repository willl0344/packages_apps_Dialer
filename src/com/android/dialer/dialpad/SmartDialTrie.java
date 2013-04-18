/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.dialpad;

import android.text.TextUtils;

import com.android.dialer.dialpad.SmartDialCache.ContactNumber;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Prefix trie where the only allowed characters are the characters '0' to '9'. Multiple contacts
 * can occupy the same nodes.
 *
 * <p>Provides functions to get all contacts that lie on or below a node.
 * This is useful for retrieving all contacts that start with that prefix.</p>
 *
 * <p>Also contains special logic to handle NANP numbers in the case that the user is from a region
 * that uses NANP numbers.</p>
 */
public class SmartDialTrie {
    final Node mRoot = new Node();
    private int mSize = 0;
    private final char[] mCharacterMap;
    private final boolean mFormatNanp;

    // Static set of all possible country codes in the world
    public static Set<String> sCountryCodes = null;

    public SmartDialTrie() {
        // Use the latin letter to digit map by default if none provided
        this(SmartDialNameMatcher.LATIN_LETTERS_TO_DIGITS, false);
    }

    /**
     * Creates a new SmartDialTrie.
     *
     * @param formatNanp True if inserted numbers are to be treated as NANP numbers
     * such that numbers are automatically broken up by country prefix and area code.
     */
    @VisibleForTesting
    public SmartDialTrie(boolean formatNanp) {
        this(SmartDialNameMatcher.LATIN_LETTERS_TO_DIGITS, formatNanp);
    }

    /**
     * Creates a new SmartDialTrie.
     *
     * @param charMap Mapping of characters to digits to use when inserting names into the trie.
     * @param formatNanp True if inserted numbers are to be treated as NANP numbers
     * such that numbers are automatically broken up by country prefix and area code.
     */
    public SmartDialTrie(char[] charMap, boolean formatNanp) {
        mCharacterMap = charMap;
        mFormatNanp = formatNanp;
    }

    /**
     * Returns all contacts in the prefix tree that correspond to this prefix.
     */
    public ArrayList<ContactNumber> getAllWithPrefix(CharSequence prefix) {
        final ArrayList<ContactNumber> result = Lists.newArrayList();
        if (TextUtils.isEmpty(prefix)) {
            return result;
        }
        Node current = mRoot;
        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);
            current = current.getChild(ch, false);
            if (current == null) {
                return result;
            }
        }
        // return all contacts that correspond to this prefix
        getAll(current, result);
        return result;
    }

    /**
     * Returns all the contacts located at and under the provided node(including its children)
     */
    private void getAll(Node root, ArrayList<ContactNumber> output) {
        if (root == null) {
            return;
        }
        if (root.getContents() != null) {
            output.addAll(root.getContents());
        }
        for (int i = 0; i < root.getChildrenSize(); i++) {
            getAll(root.getChild(i, false), output);
        }
    }

    /**
     * Adds the display name and phone number of a contact into the prefix trie.
     *
     * @param contact Desired contact to add
     */
    public void put(ContactNumber contact) {
        // Preconvert the display name into a byte array containing indexes to avoid having to
        // remap each character over multiple passes
        putForPrefix(contact, mRoot, toByteArray(contact.displayName), 0,
                contact.displayName.length(), true, true);
        // We don't need to do the same for phone numbers since we only make one pass over them.
        // Strip the calling code from the phone number here
        if (!TextUtils.isEmpty(contact.phoneNumber)) {
            // Handle country codes for numbers with a + prefix
            final int offset = getOffsetWithoutCountryCode(contact.phoneNumber);
            if (offset > 0) {
                putNumber(contact, contact.phoneNumber, offset);
            } else if (mFormatNanp) {
                // Special case handling for NANP numbers (1-xxx-xxx-xxxx)
                final String stripped = SmartDialNameMatcher.normalizeNumber(contact.phoneNumber);
                if (!TextUtils.isEmpty(stripped)) {
                    int trunkPrefixOffset = 0;
                    if (stripped.charAt(0) == '1') {
                        // If the number starts with 1, we can assume its the trunk prefix.
                        trunkPrefixOffset = 1;
                    }
                    if (stripped.length() == (10 + trunkPrefixOffset)) {
                        // Valid NANP number
                        if (trunkPrefixOffset != 0) {
                            // Add the digits that follow the 1st digit (trunk prefix)
                            // If trunkPrefixOffset is 0, we will add the number as is anyway, so
                            // don't bother.
                            putNumber(contact, stripped, trunkPrefixOffset);
                        }
                        // Add the digits that follow the next 3 digits (area code)
                        putNumber(contact, stripped, 3 + trunkPrefixOffset);
                    }
                }
            }
            putNumber(contact, contact.phoneNumber);
        }
        mSize++;
    }

    public static int getOffsetWithoutCountryCode(String number) {
        if (!TextUtils.isEmpty(number)) {
            if (number.charAt(0) == '+') {
                // check for international code here
                for (int i = 1; i <= 1 + 3; i++) {
                    if (number.length() <= i) break;
                    if (isValidCountryCode(number.substring(1, i))) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    /**
     * Used by SmartDialNameMatcher to determine which character in the phone number to start
     * the matching process from for a NANP formatted number.
     *
     * @param number Raw phone number
     * @return An empty array if the provided number does not appear to be an NANP number,
     * and an array containing integer offsets for the number (starting after the '1' prefix,
     * and the area code prefix respectively.
     */
    public static int[] getOffsetForNANPNumbers(String number) {
        int validDigits = 0;
        boolean hasPrefix = false;
        int firstOffset = 0; // Tracks the location of the first digit after the '1' prefix
        int secondOffset = 0; // Tracks the location of the first digit after the area code
        for (int i = 0; i < number.length(); i++) {
            final char ch = number.charAt(i);
            if (ch >= '0' && ch <= '9') {
                if (validDigits == 0) {
                    // Check the first digit to see if it is 1
                    if (ch == '1') {
                        if (hasPrefix) {
                            // Prefix has two '1's in a row. Invalid number, since area codes
                            // cannot start with 1, so just bail
                            break;
                        }
                        hasPrefix = true;
                        continue;
                    }
                }
                validDigits++;
                if (validDigits == 1) {
                    // Found the first digit after the country code
                    firstOffset = i;
                } else if (validDigits == 4) {
                    // Found the first digit after the area code
                    secondOffset = i;
                }
            }

        }
        if (validDigits == 10) {
            return hasPrefix ? new int[] {firstOffset, secondOffset} : new int[] {secondOffset};
        }
        return new int[0];
    }

    @VisibleForTesting
    /* package */ byte[] toByteArray(CharSequence chars) {
        final int length = chars.length();
        final byte[] result = new byte[length];
        char c;
        for (int i = 0; i < length; i++) {
            c = SmartDialNameMatcher.remapAccentedChars(chars.charAt(i));
            if (c >= 'a' && c <= 'z') {
                result[i] = (byte) (mCharacterMap[c - 'a'] - '0');
            } else {
                result[i] = -1;
            }
        }
        return result;
    }

    /**
     * Puts a phone number and its associated contact into the prefix trie.
     *
     * @param contact - Contact to add to the trie
     * @param phoneNumber - Phone number of the contact
    */
    private void putNumber(ContactNumber contact, String phoneNumber) {
        putNumber(contact, phoneNumber, 0);
    }

    /**
     * Puts a phone number and its associated contact into the prefix trie.
     *
     * @param contact - Contact to add to the trie
     * @param phoneNumber - Phone number of the contact
     * @param offset - The nth character of the phone number to start from
     */
    private void putNumber(ContactNumber contact, String phoneNumber, int offSet) {
        Preconditions.checkArgument(offSet < phoneNumber.length());
        Node current = mRoot;
        final int length = phoneNumber.length();
        char ch;
        for (int i = offSet; i < length; i++) {
            ch = phoneNumber.charAt(i);
            if (ch >= '0' && ch <= '9') {
                current = current.getChild(ch, true);
            }
        }
        current.add(contact);
    }

    /**
     * Place an contact into the trie using at the provided node using the provided prefix. Uses as
     * the input prefix a byte array instead of a CharSequence, as we will be traversing the array
     * multiple times and it is more efficient to pre-convert the prefix into indexes before hand.
     *
     * @param contact Contact to put
     * @param root Root node to use as the starting point
     * @param prefix Sequence of bytes which represent the index at
     * @param start - Starting index of the byte array
     * @param end - Last index(not inclusive) of the byte array
     * @param isFullName If true, prefix will be treated as a full name and recursive calls to add
     *        initial matches as well as name token matches into the trie will be made.
     * @param addInitials If true, recursive calls to add initial matches into the trie will be
     *        made.
     */
    private void putForPrefix(ContactNumber contact, Node root, byte[] prefix, int start, int end,
            boolean isFullName, boolean addInitials) {
        Node current = root;
        Node initialNode = root;
        final int length = end;
        boolean atSeparator = true;
        byte index;
        for (int i = start; i < length; i++) {
            index = prefix[i];
            if (index > -1) {
                if (atSeparator) {
                    atSeparator = false;
                    // encountered a new name token, so add this token into the tree starting from
                    // the root node
                    if (addInitials || isFullName) {
                        if (initialNode != this.mRoot) {
                            if (isFullName) {
                                putForPrefix(contact, this.mRoot, prefix, i, prefix.length, false,
                                        true);
                            }
                            putForPrefix(contact, initialNode,
                                    prefix, i, prefix.length, false, false);
                        }
                    }

                    // Finding a new name token means we find a new initial character as well.
                    // Use initialNode to track the current node at which initial characters match.
                    // E.g. If we are at character m of John W S(m)ith, then the current initial
                    // node is indexed by the characters JWS.
                    initialNode = initialNode.getChild(index, true);
                }
                current = current.getChild(index, true);
            } else {
                atSeparator = true;
            }
        }
        current.add(contact);
    }

    @VisibleForTesting
    public int size() {
        return mSize;
    }

    @VisibleForTesting
    /* package */ static class Node {
        Node[] mChildren;
        private ArrayList<ContactNumber> mContents;

        public Node() {
            // don't allocate array or contents unless needed
        }

        public int getChildrenSize() {
            if (mChildren == null) {
                return -1;
            }
            return mChildren.length;
        }

        /**
         * Returns a specific child of the current node.
         *
         * @param index Index of the child to return.
         * @param createIfDoesNotExist Whether or not to create a node in that child slot if one
         *        does not already currently exist.
         * @return The existing or newly created child, or {@literal null} if the child does not
         *         exist and createIfDoesNotExist is false.
         */
        public Node getChild(int index, boolean createIfDoesNotExist) {
            if (createIfDoesNotExist) {
                if (mChildren == null) {
                    mChildren = new Node[10];
                }
                if (mChildren[index] == null) {
                    mChildren[index] = new Node();
                }
            } else {
                if (mChildren == null) {
                    return null;
                }
            }
            return mChildren[index];
        }

        /**
         * Same as getChild(int index, boolean createIfDoesNotExist), but takes a character from '0'
         * to '9' as an index.
         */
        public Node getChild(char index, boolean createIfDoesNotExist) {
            return getChild(index - '0', createIfDoesNotExist);
        }

        public void add(ContactNumber contact) {
            if (mContents == null) {
                mContents = Lists.newArrayList();
            }
            mContents.add(contact);
        }

        public ArrayList<ContactNumber> getContents() {
            return mContents;
        }
    }

    private static boolean isValidCountryCode(String countryCode) {
        if (sCountryCodes == null) {
            sCountryCodes = initCountryCodes();
        }
        return sCountryCodes.contains(countryCode);
    }

    private static Set<String> initCountryCodes() {
        final HashSet<String> result = new HashSet<String>();
        result.add("1");
        result.add("7");
        result.add("20");
        result.add("27");
        result.add("30");
        result.add("31");
        result.add("32");
        result.add("33");
        result.add("34");
        result.add("36");
        result.add("39");
        result.add("40");
        result.add("41");
        result.add("43");
        result.add("44");
        result.add("45");
        result.add("46");
        result.add("47");
        result.add("48");
        result.add("49");
        result.add("51");
        result.add("52");
        result.add("53");
        result.add("54");
        result.add("55");
        result.add("56");
        result.add("57");
        result.add("58");
        result.add("60");
        result.add("61");
        result.add("62");
        result.add("63");
        result.add("64");
        result.add("65");
        result.add("66");
        result.add("81");
        result.add("82");
        result.add("84");
        result.add("86");
        result.add("90");
        result.add("91");
        result.add("92");
        result.add("93");
        result.add("94");
        result.add("95");
        result.add("98");
        result.add("211");
        result.add("212");
        result.add("213");
        result.add("216");
        result.add("218");
        result.add("220");
        result.add("221");
        result.add("222");
        result.add("223");
        result.add("224");
        result.add("225");
        result.add("226");
        result.add("227");
        result.add("228");
        result.add("229");
        result.add("230");
        result.add("231");
        result.add("232");
        result.add("233");
        result.add("234");
        result.add("235");
        result.add("236");
        result.add("237");
        result.add("238");
        result.add("239");
        result.add("240");
        result.add("241");
        result.add("242");
        result.add("243");
        result.add("244");
        result.add("245");
        result.add("246");
        result.add("247");
        result.add("248");
        result.add("249");
        result.add("250");
        result.add("251");
        result.add("252");
        result.add("253");
        result.add("254");
        result.add("255");
        result.add("256");
        result.add("257");
        result.add("258");
        result.add("260");
        result.add("261");
        result.add("262");
        result.add("263");
        result.add("264");
        result.add("265");
        result.add("266");
        result.add("267");
        result.add("268");
        result.add("269");
        result.add("290");
        result.add("291");
        result.add("297");
        result.add("298");
        result.add("299");
        result.add("350");
        result.add("351");
        result.add("352");
        result.add("353");
        result.add("354");
        result.add("355");
        result.add("356");
        result.add("357");
        result.add("358");
        result.add("359");
        result.add("370");
        result.add("371");
        result.add("372");
        result.add("373");
        result.add("374");
        result.add("375");
        result.add("376");
        result.add("377");
        result.add("378");
        result.add("379");
        result.add("380");
        result.add("381");
        result.add("382");
        result.add("385");
        result.add("386");
        result.add("387");
        result.add("389");
        result.add("420");
        result.add("421");
        result.add("423");
        result.add("500");
        result.add("501");
        result.add("502");
        result.add("503");
        result.add("504");
        result.add("505");
        result.add("506");
        result.add("507");
        result.add("508");
        result.add("509");
        result.add("590");
        result.add("591");
        result.add("592");
        result.add("593");
        result.add("594");
        result.add("595");
        result.add("596");
        result.add("597");
        result.add("598");
        result.add("599");
        result.add("670");
        result.add("672");
        result.add("673");
        result.add("674");
        result.add("675");
        result.add("676");
        result.add("677");
        result.add("678");
        result.add("679");
        result.add("680");
        result.add("681");
        result.add("682");
        result.add("683");
        result.add("685");
        result.add("686");
        result.add("687");
        result.add("688");
        result.add("689");
        result.add("690");
        result.add("691");
        result.add("692");
        result.add("800");
        result.add("808");
        result.add("850");
        result.add("852");
        result.add("853");
        result.add("855");
        result.add("856");
        result.add("870");
        result.add("878");
        result.add("880");
        result.add("881");
        result.add("882");
        result.add("883");
        result.add("886");
        result.add("888");
        result.add("960");
        result.add("961");
        result.add("962");
        result.add("963");
        result.add("964");
        result.add("965");
        result.add("966");
        result.add("967");
        result.add("968");
        result.add("970");
        result.add("971");
        result.add("972");
        result.add("973");
        result.add("974");
        result.add("975");
        result.add("976");
        result.add("977");
        result.add("979");
        result.add("992");
        result.add("993");
        result.add("994");
        result.add("995");
        result.add("996");
        result.add("998");
        return result;
    }
}