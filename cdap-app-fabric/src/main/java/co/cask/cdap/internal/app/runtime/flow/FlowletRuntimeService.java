/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.internal.app.runtime.flow;

import co.cask.cdap.api.annotation.TransactionControl;
import co.cask.cdap.api.flow.flowlet.Callback;
import co.cask.cdap.api.flow.flowlet.Flowlet;
import co.cask.cdap.api.flow.flowlet.FlowletContext;
import co.cask.cdap.common.logging.LoggingContextAccessor;
import co.cask.cdap.data2.transaction.Transactions;
import co.cask.cdap.internal.app.runtime.DataFabricFacade;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * This class represents lifecycle of a {@link Flowlet}, Start, Stop, Suspend and Resume.
 */
final class FlowletRuntimeService extends AbstractIdleService {

  private static final Logger LOG = LoggerFactory.getLogger(FlowletRuntimeService.class);

  private final Flowlet flowlet;
  private final BasicFlowletContext flowletContext;
  private final Collection<? extends ProcessSpecification<?>> processSpecs;
  private final Callback txCallback;
  private final DataFabricFacade dataFabricFacade;
  private final Service serviceHook;

  private FlowletProcessDriver flowletProcessDriver;

  FlowletRuntimeService(Flowlet flowlet, BasicFlowletContext flowletContext,
                        Collection<? extends ProcessSpecification<?>> processSpecs,
                        Callback txCallback, DataFabricFacade dataFabricFacade,
                        Service serviceHook) {
    this.flowlet = flowlet;
    this.flowletContext = flowletContext;
    this.processSpecs = processSpecs;
    this.txCallback = txCallback;
    this.dataFabricFacade = dataFabricFacade;
    this.serviceHook = serviceHook;
  }

  @Override
  protected void startUp() throws Exception {
    LoggingContextAccessor.setLoggingContext(flowletContext.getLoggingContext());
    flowletContext.getProgramMetrics().increment("process.instance", 1);
    flowletProcessDriver = new FlowletProcessDriver(flowletContext, dataFabricFacade, txCallback, processSpecs);

    serviceHook.startAndWait();
    initFlowlet();
    flowletProcessDriver.startAndWait();
    LOG.info("Started Flowlet '{}' for Flow '{}'. Flowlet details: [{}]",
             flowletContext.getFlowletId(), flowletContext.getFlowId(), flowletContext);
  }

  @Override
  protected void shutDown() throws Exception {
    LoggingContextAccessor.setLoggingContext(flowletContext.getLoggingContext());
    if (flowletProcessDriver != null) {
      stopService(flowletProcessDriver);
    }
    destroyFlowlet();
    LOG.info("Stopped Flowlet '{}' Instance {} for Flow '{}'", flowletContext.getFlowletId(),
             flowletContext.getInstanceId(), flowletContext.getFlowId());
    stopService(serviceHook);
  }

  /**
   * Suspend the running of flowlet. This method will block until the flowlet process is stopped.
   * This method only get called from FlowletProgramController, and controller handled state transition and
   * make sure thread safety.
   */
  void suspend() {
    flowletProcessDriver.stopAndWait();

    // After a FlowletProcessDriver stopped, it cannot be started again
    // Hence copying all states to a new instance and start it again on resuming.
    flowletProcessDriver = new FlowletProcessDriver(flowletProcessDriver);
  }

  /**
   * Resume the running of flowlet.
   * This method only get called from FlowletProgramController, and controller handled state transition and
   * make sure thread safety.
   */
  void resume() {
    flowletProcessDriver.startAndWait();
  }

  private void initFlowlet() throws Exception {
    LOG.debug("Initializing flowlet: {}", flowletContext);
    TransactionControl txControl = Transactions.getTransactionControl(flowletContext.getDefaultTxControl(),
                                                                       Flowlet.class, flowlet, "initialize",
                                                                       FlowletContext.class);
    flowletContext.initializeProgram(flowlet, txControl, false);
    LOG.debug("Flowlet initialized: {}", flowletContext);
  }

  private void destroyFlowlet() {
    LOG.debug("Destroying flowlet: {}", flowletContext);
    TransactionControl txControl = Transactions.getTransactionControl(flowletContext.getDefaultTxControl(),
                                                                      Flowlet.class, flowlet, "destroy");
    flowletContext.destroyProgram(flowlet, txControl, false);
    LOG.debug("Flowlet destroyed: {}", flowletContext);
  }

  /**
   * Stops the given service and wait for the completion. If there is exception, just log.
   */
  private void stopService(Service service) {
    try {
      service.stopAndWait();
    } catch (Throwable t) {
      LOG.warn("Exception when stopping service {}", service);
    }
  }
}
