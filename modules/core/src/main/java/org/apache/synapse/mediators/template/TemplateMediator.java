/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.synapse.mediators.template;

import org.apache.synapse.Mediator;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.SynapseLog;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.mediators.AbstractListMediator;
import org.apache.synapse.mediators.MediatorFaultHandler;

import java.util.Collection;
import java.util.Stack;

/**
 * This mediator is responsible for handling function templates for synapse. This will parse parameters
 * passed from an <invoke> mediator and will be made available to sequences defined within this template.
 * parameters will be accessible through synapse 'function stack'. Users should access these through an
 *  xpath extension var or function , defined under function scope
 *  ie:- $func:p1 or get-property('func','p2')
 */
public class TemplateMediator extends AbstractListMediator {

    private Collection<TemplateParam> templateParams;

    private String eipPatternName;
    private String fileName;
    /** flag to ensure that each and every sequence is initialized and destroyed atmost once */
    private boolean initialized = false;
    /** is this definition dynamic */
    private boolean dynamic = false;
    /** Name of the artifact container which the template deployed from */
    private String artifactContainerName;
    /** whether the template edited via the management console or not */
    private boolean isEdited;
    /** flag to identify whether the mediator is currently initializing */
    private boolean initializing = false;

    private String errorHandler = null;

    /** The local entry key name */
    private String localEntryKey = null;

    public String getLocalEntryKey() {
        return localEntryKey;
    }

    public void setLocalEntryKey(String localEntryKey) {
        this.localEntryKey = localEntryKey;
    }

    public void setParameters(Collection<TemplateParam> paramNames) {
        this.templateParams = paramNames;
    }

    public Collection<TemplateParam> getParameters() {
        return templateParams;
    }

    public void setName(String name) {
        this.eipPatternName = name;
    }

    public String getName() {
        return eipPatternName;
    }

    public String getArtifactContainerName() {
        return artifactContainerName;
    }

    public void setArtifactContainerName(String artifactContainerName) {
        this.artifactContainerName = artifactContainerName;
    }

    public boolean isEdited() {
        return isEdited;
    }

    public void setIsEdited(boolean isEdited) {
        this.isEdited = isEdited;
    }

    public String getErrorHandler() {
        return errorHandler;
    }

    public void setErrorHandler(String errorHandler) {
        this.errorHandler = errorHandler;
    }

    public boolean mediate(MessageContext synCtx) {

        if (synCtx.getEnvironment().isDebuggerEnabled()) {
            if (super.divertMediationRoute(synCtx)) {
                return true;
            }
        }

        SynapseLog synLog = getLog(synCtx);

        Mediator errorHandlerMediator = null;

        if (errorHandler != null) {
            errorHandlerMediator = synCtx.getSequence(errorHandler);

            if (errorHandlerMediator != null) {
                if (synLog.isTraceOrDebugEnabled()) {
                    synLog.traceOrDebug("Setting the onError handler : " + errorHandler +
                            " for sequence template : " + eipPatternName);
                }
                synCtx.pushFaultHandler(new MediatorFaultHandler(errorHandlerMediator));
            } else {
                synLog.auditWarn("onError handler : " + errorHandler + " for sequence template: " +
                        eipPatternName + " cannot be found");
            }
        }

        if (synLog.isTraceOrDebugEnabled()) {
            synLog.traceOrDebug("Start : EIP Sequence " + "templateParams : " + templateParams);

            if (synLog.isTraceTraceEnabled()) {
                synLog.traceTrace("Message : " + synCtx.getEnvelope());
            }
        }

        boolean result = false;
        try {
            pushFuncContextTo(synCtx);
            result = super.mediate(synCtx);
            // if we pushed an error handler, pop it from the fault stack
            // before we exit normally without an exception
            if (errorHandlerMediator != null) {
                Stack faultStack = synCtx.getFaultStack();
                if (faultStack != null && !faultStack.isEmpty()) {
                    Object faultHandler = faultStack.peek();
                    if (faultHandler instanceof MediatorFaultHandler && errorHandlerMediator
                            .equals(((MediatorFaultHandler) faultHandler).getFaultMediator())) {
                        faultStack.pop();
                    }
                }
            }
        } finally {
            if (result) {
                popFuncContextFrom(synCtx);
            }
        }
        return result;
    }

    /**
     * for each message coming to this function template ,pushes a function context containing
     * parameters into Synapse Function Stack.
     * @param synCtx  Synapse Message context
     */
    private void pushFuncContextTo(MessageContext synCtx) {
        TemplateContext funcContext = new TemplateContext(eipPatternName, templateParams);
        if (localEntryKey != null) {
            funcContext.setLocalEntryKey(localEntryKey);
        }
        //process the raw parameters parsed in
        funcContext.setupParams(synCtx);

        Object functionStackObj = synCtx.getProperty(SynapseConstants.SYNAPSE__FUNCTION__STACK);
        Stack<TemplateContext> stack = null;
        if((functionStackObj instanceof Stack)){
            stack = (Stack<TemplateContext>) functionStackObj;
        }

        //if a function stack has not already been created for this message flow create new one
        if (stack == null) {
            stack = new Stack<TemplateContext>();
            stack.push(funcContext);
            synCtx.setProperty(SynapseConstants.SYNAPSE__FUNCTION__STACK, stack);
        } else {
            stack.push(funcContext);
        }
    }

    public void popFuncContextFrom(MessageContext synCtx) {
        Stack<TemplateContext> stack = (Stack) synCtx.getProperty(SynapseConstants.SYNAPSE__FUNCTION__STACK);
        if (stack != null) {
            stack.pop();
        }
    }

    public void setFileName(String name) {
        fileName = name;
    }

    public String getFileName() {
        return fileName;
    }

    @Override
    public synchronized void init(SynapseEnvironment se) {
        if (!initialized) {
            initializing = true;
            super.init(se);
            initialized = true;
            initializing = false;
            if (!isDynamic()) {
                // mark as available, if this is marked previously as unavailable in the environment
                se.clearUnavailabilityOfArtifact(eipPatternName);
            }
        }
    }

    @Override
    public synchronized void destroy() {
        if (initialized) {
            super.destroy();
            initialized = false;
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public boolean isInitializing(){
        return initializing;
    }
    
    /**
     * Is this a dynamic template?
     *
     * @return true if dynamic
     */
    public boolean isDynamic() {
        return dynamic;
    }

    /**
     * Mark this as a dynamic template
     *
     * @param dynamic true if this is a dynamic template
     */
    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
    }
}
