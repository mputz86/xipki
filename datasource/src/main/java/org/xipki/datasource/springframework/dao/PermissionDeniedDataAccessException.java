// #THIRDPARTY# Spring Framework

/*
 * Copyright 2002-2006 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.xipki.datasource.springframework.dao;

/**
 *
 * Exception thrown when the underlying resource denied a permission
 * to access a specific element, such as a specific database table.
 *
 * @author Juergen Hoeller
 */
@SuppressWarnings("serial")
public class PermissionDeniedDataAccessException extends NonTransientDataAccessException {

    /**
     * Constructor for PermissionDeniedDataAccessException.
     * @param msg the detail message
     * @param cause the root cause from the underlying data access API, such as JDBC
     */
    public PermissionDeniedDataAccessException(final String msg, final Throwable cause) {
        super(msg, cause);
    }

}
