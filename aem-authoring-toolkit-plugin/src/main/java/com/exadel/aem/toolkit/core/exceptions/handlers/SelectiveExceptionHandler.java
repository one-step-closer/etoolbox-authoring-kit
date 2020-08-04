/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.exadel.aem.toolkit.core.exceptions.handlers;

import java.util.List;
import java.util.Optional;

import com.exadel.aem.toolkit.core.exceptions.PluginException;
import org.apache.commons.lang3.ClassUtils;
import org.apache.commons.lang3.StringUtils;

/**
 * Implements the "selective" kind of {@link com.exadel.aem.toolkit.api.runtime.ExceptionHandler}, that is, the one
 * that throws {@link com.exadel.aem.toolkit.core.exceptions.PluginException}s and so terminates Maven workflow
 * in case one of the specific internal exceptions (listed ln constructor's on;ly argument) is thrown, and otherwise
 * falls back to {@code PermissiveExceptionHandler} behavior
 */
class SelectiveExceptionHandler extends PermissiveExceptionHandler {
    private List<String> criticalExceptions;
    private static final String ALL_EXCEPTIONS = "all";
    private static final String EXCEPTIONS_WILDCARD = "*";
    private static final String EXCLAMATION_MARK = "!";
    private static final String PACKAGE_POSTFIX = ".*";

    SelectiveExceptionHandler(List<String> criticalExceptions) {
        this.criticalExceptions = criticalExceptions;
    }

    /**
     * Handles exception with additional checking whether to terminate plugin execution
     * (by re-throwing more generic {@code PluginException}) or not
     * @param message Attached exception message
     * @param cause   Base exception
     */
    @Override
    public void handle(String message, Exception cause) {
        if (haltsOn(cause.getClass())) {
            throw new PluginException(cause);
        }
        super.handle(message, cause);
    }

    @Override
    public boolean haltsOn(Class<? extends Exception> exceptionType) {
        Optional result;
        boolean inverse;
        for (String exName : criticalExceptions) {
            inverse = false;
            if (exName.startsWith(EXCLAMATION_MARK)) {
                inverse = true;
                exName = exName.substring(1);
            }
            result = checkException(exceptionType, exName, inverse);
            if(result.equals(Optional.of(true))){
                return true;
            } else if (result.equals(Optional.of(false))){
                return false;
            }
        }
        return false;
    }

    private Optional checkException(Class<? extends Exception> exceptionType, String criticalException, boolean inverse){
        if (StringUtils.equalsAnyIgnoreCase(criticalException, ALL_EXCEPTIONS, EXCEPTIONS_WILDCARD)) {
            return Optional.of(!inverse);
        }
        if (criticalException.endsWith(PACKAGE_POSTFIX)) {
            if (exceptionType.getName().startsWith(criticalException.substring(0, criticalException.length() - 1))) {
                return Optional.of(!inverse);
            } else {
                return Optional.empty();
            }
        }
        try {
            Class<?> managedClass = Class.forName(criticalException);
            if (ClassUtils.isAssignable(exceptionType, managedClass)) {
                return Optional.of(!inverse);
            }
        } catch (ClassNotFoundException exception) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
