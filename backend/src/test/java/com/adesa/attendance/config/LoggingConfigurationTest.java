package com.adesa.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class LoggingConfigurationTest {

    @Test
    void productionSecurityEventsUseSeparateStderrAndDevelopmentKeepsFileLogging() throws Exception {
        Document document;
        try (InputStream config = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(config).isNotNull();
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config);
        }

        Element production = profile(document, "prod");
        Element productionAppender = elementByAttribute(production, "appender", "name", "SECURITY_CONSOLE");
        Element target = (Element) productionAppender.getElementsByTagName("target").item(0);
        Element productionLogger = elementByAttribute(production, "logger", "name", "SECURITY");
        Element productionReference = elementByAttribute(
                productionLogger, "appender-ref", "ref", "SECURITY_CONSOLE");

        assertThat(target.getTextContent()).isEqualTo("System.err");
        assertThat(productionReference).isNotNull();
        assertThat(hasAppender(production, "SECURITY_FILE")).isFalse();
        assertThat(productionLogger.getAttribute("additivity")).isEqualTo("false");

        Element nonProduction = profile(document, "!prod");
        Element fileLogger = elementByAttribute(nonProduction, "logger", "name", "SECURITY");
        assertThat(elementByAttribute(fileLogger, "appender-ref", "ref", "SECURITY_FILE")).isNotNull();
    }

    private static Element profile(Document document, String name) {
        NodeList profiles = document.getElementsByTagName("springProfile");
        for (int index = 0; index < profiles.getLength(); index++) {
            Element profile = (Element) profiles.item(index);
            if (name.equals(profile.getAttribute("name"))) {
                return profile;
            }
        }
        throw new AssertionError("Missing logging profile " + name);
    }

    private static Element elementByAttribute(Element parent, String tag, String attribute, String value) {
        NodeList elements = parent.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (value.equals(element.getAttribute(attribute))) {
                return element;
            }
        }
        throw new AssertionError("Missing " + tag + " with " + attribute + "=" + value);
    }

    private static boolean hasAppender(Element profile, String name) {
        NodeList appenders = profile.getElementsByTagName("appender");
        for (int index = 0; index < appenders.getLength(); index++) {
            if (name.equals(((Element) appenders.item(index)).getAttribute("name"))) {
                return true;
            }
        }
        return false;
    }
}
