/*
 * (C) Copyright 2012-2017 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Florent Guillaume
 */
package org.nuxeo.ecm.core.work;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.naming.NamingException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.Transaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.junit.Assert;
import org.junit.Test;
import org.nuxeo.ecm.core.api.ConcurrentUpdateException;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.core.work.api.WorkQueueMetrics;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.NXRuntimeTestCase;
import org.nuxeo.runtime.transaction.TransactionHelper;

public class WorkManagerTXTest extends NXRuntimeTestCase {

    protected static final String CATEGORY = "SleepWork";

    protected static final String QUEUE = "SleepWork";

    protected WorkManager service;

    void assertMetrics(long scheduled, long running, long completed, long cancelled) {
        assertEquals(new WorkQueueMetrics(QUEUE, scheduled, running, completed, cancelled), service.getMetrics(QUEUE));
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        deployBundle("org.nuxeo.runtime.jtajca");
        deployBundle("org.nuxeo.ecm.core.event");
        deployContrib("org.nuxeo.ecm.core.event.test", "test-workmanager-config.xml");
    }

    @Override
    protected void postSetUp() throws Exception {
        service = Framework.getLocalService(WorkManager.class);
        assertNotNull(service);
        assertMetrics(0, 0, 0, 0);
        TransactionHelper.startTransaction();
    }

    @Override
    public void tearDown() throws Exception {
        if (TransactionHelper.isTransactionActiveOrMarkedRollback()) {
            TransactionHelper.setTransactionRollbackOnly();
            TransactionHelper.commitOrRollbackTransaction();
        }
        super.tearDown();
    }

    @Test
    public void testWorkManagerPostCommit() throws Exception {
        int duration = 1000; // 1s
        SleepWork work = new SleepWork(duration, false);
        service.schedule(work, true);
        assertMetrics(0, 0, 0, 0);

        TransactionHelper.commitOrRollbackTransaction();

        Thread.sleep(duration + 1000);
        assertMetrics(0, 0, 1, 0);
    }

    @Test
    public void testWorkManagerRollback() throws Exception {
        Assert.assertTrue(TransactionHelper.isTransactionActive());
        int duration = 1000; // 1s
        SleepWork work = new SleepWork(duration, false);
        service.schedule(work, true);
        assertMetrics(0, 0, 0, 0);

        TransactionHelper.setTransactionRollbackOnly();
        TransactionHelper.commitOrRollbackTransaction();
        assertMetrics(0, 0, 0, 0);

    }

    public static class TestWork extends AbstractWork {

        private static final long serialVersionUID = 1L;

        public int retryCount;

        public int throwCount;

        public int runs;

        public boolean throwDuringXAResourceEnd;

        @Override
        public String getTitle() {
            return getClass().getName();
        }

        @Override
        public int getRetryCount() {
            return retryCount;
        }

        @Override
        public void work() {
            runs++;
            if (--throwCount >= 0) {
                if (!throwDuringXAResourceEnd) {
                    throw new ConcurrentUpdateException("run " + runs);
                } else {
                    XAResource xaRes = new XAResource() {
                        @Override
                        public void commit(Xid xid, boolean onePhase) throws XAException {
                        }

                        @Override
                        public void start(Xid xid, int flags) throws XAException {
                        }

                        @Override
                        public void end(Xid xid, int flags) throws XAException {
                            // similar code to what we have in SessionImpl#end to deal with ConcurrentUpdateException
                            Exception e = new ConcurrentUpdateException("end run " + runs);
                            TransactionHelper.noteSuppressedException(e);
                            TransactionHelper.setTransactionRollbackOnly();
                        }

                        @Override
                        public void forget(Xid xid) throws XAException {
                        }

                        @Override
                        public int getTransactionTimeout() throws XAException {
                            return 0;
                        }

                        @Override
                        public boolean isSameRM(XAResource xares) throws XAException {
                            return false;
                        }

                        @Override
                        public int prepare(Xid xid) throws XAException {
                            return 0;
                        }

                        @Override
                        public Xid[] recover(int flag) throws XAException {
                            return null;
                        }

                        @Override
                        public void rollback(Xid xid) throws XAException {
                        }

                        @Override
                        public boolean setTransactionTimeout(int seconds) throws XAException {
                            return true;
                        }
                    };
                    Transaction transaction;
                    try {
                        transaction = TransactionHelper.lookupTransactionManager().getTransaction();
                        transaction.enlistResource(xaRes);
                    } catch (SystemException | NamingException | RollbackException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    @Test
    public void testWorkRetryAfterExceptionDuringWork() {
        doTestWorkRetryAfterException(false, "");
    }

    @Test
    public void testWorkRetryAfterExceptionDuringCommit() {
        doTestWorkRetryAfterException(true, "end ");
    }

    protected void doTestWorkRetryAfterException(boolean throwDuringXAResourceEnd, String messagePrefix) {
        TransactionHelper.commitOrRollbackTransaction();

        // regular run
        TestWork work = new TestWork();
        work.retryCount = 0;
        work.throwCount = 0;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        work.run();
        assertEquals(1, work.runs);

        // retry once
        work = new TestWork();
        work.retryCount = 1;
        work.throwCount = 1;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        work.run();
        assertEquals(2, work.runs);

        // retry twice
        work = new TestWork();
        work.retryCount = 2;
        work.throwCount = 2;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        work.run();
        assertEquals(3, work.runs);

        // fail immediately
        work = new TestWork();
        work.retryCount = 0;
        work.throwCount = 1;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        try {
            work.run();
            fail();
        } catch (RuntimeException e) {
            assertEquals(1, work.runs);
            assertTrue(e.getMessage(), e.getMessage().startsWith("Work failed after 0 retries"));
            Throwable cause = e.getCause();
            assertTrue(String.valueOf(cause), cause instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 1", cause.getMessage());
            assertEquals(0, cause.getSuppressed().length);
        }

        // retry twice and fail, recording suppressed exceptions
        work = new TestWork();
        work.retryCount = 1;
        work.throwCount = 2;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        try {
            work.run();
            fail();
        } catch (RuntimeException e) {
            assertEquals(2, work.runs);
            assertTrue(e.getMessage(), e.getMessage().startsWith("Work failed after 1 retry"));
            Throwable cause = e.getCause();
            assertTrue(String.valueOf(cause), cause instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 1", cause.getMessage());
            assertEquals(1, cause.getSuppressed().length);
            Throwable suppressed0 = cause.getSuppressed()[0];
            assertTrue(suppressed0 instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 2", suppressed0.getMessage());
        }

        // retry 3 times and fail, recording suppressed exceptions
        work = new TestWork();
        work.retryCount = 2;
        work.throwCount = 3;
        work.throwDuringXAResourceEnd = throwDuringXAResourceEnd;
        try {
            work.run();
            fail();
        } catch (RuntimeException e) {
            assertEquals(3, work.runs);
            assertTrue(e.getMessage(), e.getMessage().startsWith("Work failed after 2 retries"));
            Throwable cause = e.getCause();
            assertTrue(String.valueOf(cause), cause instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 1", cause.getMessage());
            assertEquals(2, cause.getSuppressed().length);
            Throwable suppressed0 = cause.getSuppressed()[0];
            assertTrue(suppressed0 instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 2", suppressed0.getMessage());
            Throwable suppressed1 = cause.getSuppressed()[1];
            assertTrue(suppressed1 instanceof ConcurrentUpdateException);
            assertEquals(messagePrefix + "run 3", suppressed1.getMessage());
        }
    }

}