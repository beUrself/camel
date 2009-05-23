/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.file;

import org.apache.camel.Exchange;
import org.apache.camel.impl.LoggingExceptionHandler;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.Synchronization;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * On completion strategy that performs the nessasary work after the {@link Exchange} has been processed.
 * <p/>
 * The work is for instance to move the processed file into a backup folder, delete the file or
 * in case of processing failure do a rollback. 
 *
 * @version $Revision$
 */
public class GenericFileOnCompletion<T> implements Synchronization {

    private final transient Log log = LogFactory.getLog(GenericFileOnCompletion.class);
    private GenericFileEndpoint<T> endpoint;
    private GenericFileOperations<T> operations;
    private ExceptionHandler exceptionHandler;

    public GenericFileOnCompletion(GenericFileEndpoint<T> endpoint, GenericFileOperations<T> operations) {
        this.endpoint = endpoint;
        this.operations = operations;
    }

    @SuppressWarnings("unchecked")
    public void onComplete(Exchange exchange) {
        onCompletion((GenericFileExchange<T>) exchange);
    }

    @SuppressWarnings("unchecked")
    public void onFailure(Exchange exchange) {
        onCompletion((GenericFileExchange<T>) exchange);
    }

    public ExceptionHandler getExceptionHandler() {
        if (exceptionHandler == null) {
            exceptionHandler = new LoggingExceptionHandler(getClass());
        }
        return exceptionHandler;
    }

    public void setExceptionHandler(ExceptionHandler exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
    }

    protected void onCompletion(GenericFileExchange<T> exchange) {
        GenericFileProcessStrategy<T> processStrategy = endpoint.getGenericFileProcessStrategy();

        // after processing
        final GenericFile<T> file = exchange.getGenericFile();
        boolean failed = exchange.isFailed();

        if (log.isDebugEnabled()) {
            log.debug("Done processing file: " + file + " using exchange: " + exchange);
        }

        // commit or rollback
        boolean committed = false;
        try {
            if (!failed) {
                // commit the file strategy if there was no failure or already handled by the DeadLetterChannel
                processStrategyCommit(processStrategy, exchange, file);
                committed = true;
            } else {
                if (exchange.getException() != null) {
                    // if the failure was an exception then handle it
                    handleException(exchange.getException());
                }
            }
        } finally {
            if (!committed) {
                processStrategyRollback(processStrategy, exchange, file);
            }
        }

    }

    /**
     * Strategy when the file was processed and a commit should be executed.
     *
     * @param processStrategy the strategy to perform the commit
     * @param exchange        the exchange
     * @param file            the file processed
     */
    @SuppressWarnings("unchecked")
    protected void processStrategyCommit(GenericFileProcessStrategy<T> processStrategy,
                                         GenericFileExchange<T> exchange, GenericFile<T> file) {
        if (endpoint.isIdempotent()) {
            // only add to idempotent repository if we could process the file
            // only use the filename as the key as the file could be moved into a done folder
            endpoint.getIdempotentRepository().add(file.getFileName());
        }

        try {
            if (log.isTraceEnabled()) {
                log.trace("Committing remote file strategy: " + processStrategy + " for file: " + file);
            }
            processStrategy.commit(operations, endpoint, exchange, file);
        } catch (Exception e) {
            handleException(e);
        }
    }

    /**
     * Strategy when the file was not processed and a rollback should be executed.
     *
     * @param processStrategy the strategy to perform the commit
     * @param exchange        the exchange
     * @param file            the file processed
     */
    protected void processStrategyRollback(GenericFileProcessStrategy<T> processStrategy,
                                           GenericFileExchange<T> exchange, GenericFile<T> file) {
        if (log.isWarnEnabled()) {
            log.warn("Rolling back remote file strategy: " + processStrategy + " for file: " + file);
        }
        try {
            processStrategy.rollback(operations, endpoint, exchange, file);
        } catch (Exception e) {
            handleException(e);
        }
    }

    protected void handleException(Throwable t) {
        Throwable newt = (t == null) ? new IllegalArgumentException("Handling [null] exception") : t;
        getExceptionHandler().handleException(newt);
    }

    @Override
    public String toString() {
        return "GenericFileOnCompletion";
    }
}
