/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.spifly.sample;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

public class TestSaxParserFactory extends SAXParserFactory {

    @Override
    public boolean getFeature(String name) throws ParserConfigurationException,
            SAXNotRecognizedException, SAXNotSupportedException {
        return false;
    }

    @Override
    public SAXParser newSAXParser() throws ParserConfigurationException,
            SAXException {
        throw new ParserConfigurationException("This one doesn't actually work");
    }

    @Override
    public void setFeature(String name, boolean value)
            throws ParserConfigurationException, SAXNotRecognizedException,
            SAXNotSupportedException {
    }

}
