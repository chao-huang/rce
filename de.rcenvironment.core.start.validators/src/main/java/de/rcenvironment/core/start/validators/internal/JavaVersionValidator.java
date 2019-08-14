/*
 * Copyright 2006-2019 DLR, Germany
 * 
 * SPDX-License-Identifier: EPL-1.0
 * 
 * http://www.rcenvironment.de/
 */

package de.rcenvironment.core.start.validators.internal;

import java.util.Optional;
import org.osgi.service.component.annotations.Component;
import de.rcenvironment.core.start.common.validation.api.InstanceValidationResult;
import de.rcenvironment.core.start.common.validation.api.InstanceValidationResultFactory;
import de.rcenvironment.core.start.common.validation.spi.DefaultInstanceValidator;
import de.rcenvironment.core.start.common.validation.spi.InstanceValidator;

/**
 * Due to Mantis issue 16338, we require at least JRE 1.8u161. Since the OSGI startup properties only allow us to specify the major version
 * (i.e., they allow us to require Java 8, but they do not allow us to require 8u161), we perform our own version check during startup.
 * 
 * This check becomes obsolete once we require Java 9 and should be removed at that point.
 * 
 * @author Alexander Weinert
 */
@Component(service = InstanceValidator.class)
public class JavaVersionValidator extends DefaultInstanceValidator {

    private static final String VALIDATION_DISPLAY_NAME = "Java Runtime Version";

    private static final int REQUIRED_UPDATE_VERSION = 161;

    @Override
    public InstanceValidationResult validate() {

        /*
         * Starting with Java 9, the correct way to check the version would be to query java.lang.Runtime.Version. This class, however, was
         * only introduced in Java 9 and is thus not available for our check for Java 8u161. Hence, we have to rely on the system property
         * java.version.
         */
        final String javaVersion = System.getProperty("java.version");

        final Optional<Boolean> isGreaterThan8u161 = isVersionCompatible(javaVersion);
        
        if (isGreaterThan8u161.isPresent()) {
            if (isGreaterThan8u161.get()) {
                return InstanceValidationResultFactory.createResultForPassed(VALIDATION_DISPLAY_NAME);
            } else {
                final String logMessage =
                    String.format("Java runtime version 8u161 required. Current java runtime version: %s", javaVersion);
                return InstanceValidationResultFactory.createResultForFailureWhichRequiresInstanceShutdown(VALIDATION_DISPLAY_NAME,
                    logMessage, logMessage);
            }
        } else {
            final String logMessage =
                String.format("Could not parse java version string: %s. Proceeding, but component authorization may not work", javaVersion);
            return InstanceValidationResultFactory.createResultForFailureWhichAllowesToProceed(VALIDATION_DISPLAY_NAME, logMessage,
                logMessage);
        }
    }

    /**
     * 
     * 
     * @param javaVersion A string representing a java version.
     * 
     * @return True, if the given string represents a version later than 8u161, false if it represents an earlier one, and an empty optional
     *         if we are unable to parse the version string.
     */
    private Optional<Boolean> isVersionCompatible(final String javaVersion) {
        /**
         * The java version string does not have a consistent format: Java 7 and 8 identify as 1.7.X_XXX and 1.8.X_XXX, respectively, while
         * from Java 9 onwards the leading "1." is dropped and the versions identify as 9.X.X, 10.X.X, 11.X.X, and so on. At the time of
         * writing, only Java versions up to Java 11 are available and it is unclear how the format of the version string will evolve over
         * time. Hence, we opt for a conservative approach here and only return results if we can identify the version string as one of the
         * above.
         */

        if (javaVersion.startsWith("1.7")) {
            // This should never occur, since from RCE 9.0 onwards we require Java 8, which is checked at an earlier point in the startup
            // process.
            return Optional.of(false);
        } else if (javaVersion.startsWith("9") || javaVersion.startsWith("10") || javaVersion.startsWith("11")) {
            return Optional.of(true);
        } else if (javaVersion.startsWith("1.8")) {
            try {
                return Optional.of(tryCheckJava8VersionString(javaVersion));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }

    }

    /**
     * @throws IllegalArgumentException If the given string is not of the form 1.8.X_YYY, where YYY indicates an integer.
     * @param javaVersion The string obtained from the system property java.version
     * @return True if the given version is later than 8u161, false otherwise.
     */
    private boolean tryCheckJava8VersionString(String javaVersion) {
        // The version string of java 8 is of the form 1.8.X_YYY, where YYY designates the update-version of the runtime. We aim to
        // parse the YYY-part and check it for being greater or equal to 161.
        if (!javaVersion.contains("_")) {
            final String errorMessage =
                String.format("Could not locate underscore indicating start of update-version. Java version string: %s", javaVersion);
            throw new IllegalArgumentException(errorMessage);
        }
        final int underscoreIndex = javaVersion.indexOf('_');
        final StringBuilder updateStringBuilder = new StringBuilder();

        int currentIndex = underscoreIndex + 1;
        char currentChar = javaVersion.charAt(currentIndex);
        while (currentIndex < javaVersion.length() && Character.isDigit(currentChar)) {
            updateStringBuilder.append(currentChar);
            currentIndex += 1;
            if (currentIndex < javaVersion.length()) {
                currentChar = javaVersion.charAt(currentIndex);
            }
        }

        try {
            final int updateVersion = Integer.parseInt(updateStringBuilder.toString());
            return updateVersion >= REQUIRED_UPDATE_VERSION;
        } catch (NumberFormatException e) {
            final String errorMessage =
                String.format("Could not parse update version %s. Java version string: %s", updateStringBuilder, javaVersion);
            throw new IllegalArgumentException(errorMessage);
        }
    }

}