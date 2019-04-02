package com.launchdarkly.android.flagstore;

import android.os.Looper;
import android.support.annotation.NonNull;

import com.launchdarkly.android.FeatureFlagChangeListener;

import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.checkOrder;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.isA;
import static org.easymock.EasyMock.newCapture;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public abstract class FlagStoreManagerTest extends EasyMockSupport {

    public abstract FlagStoreManager createFlagStoreManager(String mobileKey, FlagStoreFactory flagStoreFactory);

    @Test
    public void initialFlagStoreIsNull() {
        final FlagStoreManager manager = createFlagStoreManager("testKey", null);
        assertNull(manager.getCurrentUserStore());
    }

    @Test
    public void testSwitchToUser() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(isA(StoreUpdatedListener.class));

        replayAll();

        manager.switchToUser("user1");

        verifyAll();

        assertSame(mockStore, manager.getCurrentUserStore());
    }

    @Test
    public void deletesOlderThanLastFiveStoredUsers() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore oldestStore = strictMock(FlagStore.class);
        final FlagStore fillerStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final Capture<String> oldestIdentifier = newCapture();
        final int[] oldestCountBox = {0};

        checkOrder(fillerStore, false);
        fillerStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall().anyTimes();
        fillerStore.unregisterOnStoreUpdatedListener();
        expectLastCall().anyTimes();
        //noinspection ConstantConditions
        expect(mockCreate.createFlagStore(capture(oldestIdentifier))).andReturn(oldestStore);
        oldestStore.registerOnStoreUpdatedListener(anyObject(StoreUpdatedListener.class));
        expectLastCall().anyTimes();
        oldestStore.unregisterOnStoreUpdatedListener();
        expectLastCall().anyTimes();
        expect(mockCreate.createFlagStore(anyString())).andReturn(fillerStore);
        expect(mockCreate.createFlagStore(anyString())).andReturn(fillerStore);
        expect(mockCreate.createFlagStore(anyString())).andReturn(fillerStore);
        expect(mockCreate.createFlagStore(anyString())).andReturn(fillerStore);
        expect(mockCreate.createFlagStore(anyString())).andDelegateTo(new FlagStoreFactory() {
            @Override
            public FlagStore createFlagStore(@NonNull String identifier) {
                if (identifier.equals(oldestIdentifier.getValue())) {
                    oldestCountBox[0]++;
                    return oldestStore;
                }
                return fillerStore;
            }
        });
        expect(mockCreate.createFlagStore(anyString())).andDelegateTo(new FlagStoreFactory() {
            @Override
            public FlagStore createFlagStore(@NonNull String identifier) {
                if (identifier.equals(oldestIdentifier.getValue())) {
                    oldestCountBox[0]++;
                    return oldestStore;
                }
                return fillerStore;
            }
        });
        oldestStore.delete();
        expectLastCall();

        replayAll();

        manager.switchToUser("oldest");
        manager.switchToUser("fourth");
        manager.switchToUser("third");
        manager.switchToUser("second");
        manager.switchToUser("first");
        manager.switchToUser("new");

        verifyAll();

        assertEquals(1, oldestCountBox[0]);
    }

    @Test
    public void testGetListenersForKey() {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final FeatureFlagChangeListener mockFlagListener2 = strictMock(FeatureFlagChangeListener.class);

        assertEquals(0, manager.getListenersByKey("flag").size());
        manager.registerListener("flag", mockFlagListener);
        assertEquals(1, manager.getListenersByKey("flag").size());
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener));
        assertEquals(0, manager.getListenersByKey("otherKey").size());
        manager.registerListener("flag", mockFlagListener2);
        assertEquals(2, manager.getListenersByKey("flag").size());
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener));
        assertTrue(manager.getListenersByKey("flag").contains(mockFlagListener2));
        assertEquals(0, manager.getListenersByKey("otherKey").size());
    }

    @Test
    public void listenerIsCalledOnCreate() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andAnswer(new IAnswer<Void>() {
            @Override
            public Void answer() {
                waitLatch.countDown();
                return null;
            }
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        managerListener.getValue().onStoreUpdate("flag", FlagStoreUpdateType.FLAG_CREATED);
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    @Test
    public void listenerIsCalledOnUpdate() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andAnswer(new IAnswer<Void>() {
            @Override
            public Void answer() {
                waitLatch.countDown();
                return null;
            }
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        managerListener.getValue().onStoreUpdate("flag", FlagStoreUpdateType.FLAG_UPDATED);
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }

    @Test
    public void listenerIsNotCalledOnDelete() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        managerListener.getValue().onStoreUpdate("flag", FlagStoreUpdateType.FLAG_DELETED);
        // Unfortunately we are testing that an asynchronous method is *not* called, we just have to
        // wait a bit to be sure.
        Thread.sleep(100);

        verifyAll();
    }

    @Test
    public void listenerIsNotCalledAfterUnregistering() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        manager.unRegisterListener("flag", mockFlagListener);
        managerListener.getValue().onStoreUpdate("flag", FlagStoreUpdateType.FLAG_CREATED);
        // Unfortunately we are testing that an asynchronous method is *not* called, we just have to
        // wait a bit to be sure.
        Thread.sleep(100);

        verifyAll();
    }

    @Test
    public void listenerIsCalledOnMainThread() throws InterruptedException {
        final FlagStoreFactory mockCreate = strictMock(FlagStoreFactory.class);
        final FlagStore mockStore = strictMock(FlagStore.class);
        final FlagStoreManager manager = createFlagStoreManager("testKey", mockCreate);
        final FeatureFlagChangeListener mockFlagListener = strictMock(FeatureFlagChangeListener.class);
        final Capture<StoreUpdatedListener> managerListener = newCapture();
        final CountDownLatch waitLatch = new CountDownLatch(1);

        expect(mockCreate.createFlagStore(anyString())).andReturn(mockStore);
        mockStore.registerOnStoreUpdatedListener(capture(managerListener));
        mockFlagListener.onFeatureFlagChange("flag");
        expectLastCall().andDelegateTo(new FeatureFlagChangeListener() {
            @Override
            public void onFeatureFlagChange(String flagKey) {
                assertSame(Looper.myLooper(), Looper.getMainLooper());
                waitLatch.countDown();
            }
        });

        replayAll();

        manager.switchToUser("user1");
        manager.registerListener("flag", mockFlagListener);
        managerListener.getValue().onStoreUpdate("flag", FlagStoreUpdateType.FLAG_CREATED);
        waitLatch.await(1000, TimeUnit.MILLISECONDS);

        verifyAll();
    }
}
