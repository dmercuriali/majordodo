/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package dodo.callbacks;

/**
 * Generic callback
 *
 * @author enrico.olivelli
 */
public interface SimpleCallback<T> {

    /**
     * If an error occurred than the error parameter will be not null, otherwise
     * the result parameter will be the result of the action
     *
     * @param result
     * @param error
     */
    public void onResult(T result, Throwable error);
}
