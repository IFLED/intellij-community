/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.mock;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.BaseComponent;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusFactory;
import com.intellij.util.pico.IdeaPicoContainer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.MutablePicoContainer;
import org.picocontainer.PicoContainer;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MockComponentManager extends UserDataHolderBase implements ComponentManager {
  private static final Logger LOG = Logger.getInstance("#com.intellij.mock.MockComponentManager");

  private final MessageBus myMessageBus = MessageBusFactory.newMessageBus(this);
  private final MutablePicoContainer myPicoContainer;
  private final Map<Object, Object> myInitializedComponents = new HashMap<Object, Object>(4096);

  private final Map<Class, Object> myComponents = new HashMap<Class, Object>();

  public MockComponentManager(@Nullable PicoContainer parent, @NotNull Disposable parentDisposable) {
    myPicoContainer = new IdeaPicoContainer(parent) {
      @Override
      @Nullable
      public Object getComponentInstance(final Object componentKey) {
        final Object initializedComponent = myInitializedComponents.get(componentKey);
        if (initializedComponent != null) return initializedComponent;

        final Object o = super.getComponentInstance(componentKey);
        if (o instanceof Disposable && o != MockComponentManager.this) {
          Disposer.register(MockComponentManager.this, (Disposable)o);
        }

        myInitializedComponents.put(componentKey, o);
        if (o instanceof BaseComponent && o != MockComponentManager.this) {
           ((BaseComponent)o).initComponent();
        }

        return o;
      }
    };
    myPicoContainer.registerComponentInstance(this);
    Disposer.register(parentDisposable, this);
  }

  @Override
  public BaseComponent getComponent(String name) {
    return null;
  }

  public <T> void registerService(Class<T> serviceInterface, Class<? extends T> serviceImplementation) {
    myPicoContainer.unregisterComponent(serviceInterface.getName());
    myPicoContainer.registerComponentImplementation(serviceInterface.getName(), serviceImplementation);
  }

  public <T> void registerService(Class<T> serviceImplementation) {
    registerService(serviceImplementation, serviceImplementation);
  }

  public <T> void registerService(Class<T> serviceInterface, T serviceImplementation) {
    myPicoContainer.registerComponentInstance(serviceInterface.getName(), serviceImplementation);
  }

  public <T> void addComponent(Class<T> interfaceClass, T instance) {
    myComponents.put(interfaceClass, instance);
  }

  @Override
  public <T> T getComponent(Class<T> interfaceClass) {
    final Object o = myPicoContainer.getComponentInstance(interfaceClass);
    return (T)(o != null ? o : myComponents.get(interfaceClass));
  }

  @Override
  public <T> T getComponent(Class<T> interfaceClass, T defaultImplementation) {
    return getComponent(interfaceClass);
  }

  @Override
  public boolean hasComponent(@NotNull Class interfaceClass) {
    return false;
  }

  @Override
  @NotNull
  public <T> T[] getComponents(Class<T> baseClass) {
    final List<?> list = myPicoContainer.getComponentInstancesOfType(baseClass);
    return list.toArray((T[])Array.newInstance(baseClass, 0));
  }

  @Override
  @NotNull
  public MutablePicoContainer getPicoContainer() {
    return myPicoContainer;
  }

  @Override
  public MessageBus getMessageBus() {
    return myMessageBus;
  }

  @Override
  public boolean isDisposed() {
    return false;
  }

  @Override
  public void dispose() {
    disposeComponents();
  }

  protected void disposeComponents() {
    Collection<Object> components = myInitializedComponents.values();

    for(Object component: components)    {
      if (component instanceof BaseComponent) {
        try {
          ((BaseComponent)component).disposeComponent();
        }
        catch (Throwable e) {
          LOG.error(e);
        }
      }
    }
  }

  @Override
  public <T> T[] getExtensions(final ExtensionPointName<T> extensionPointName) {
    throw new UnsupportedOperationException("getExtensions()");
  }

  @NotNull
  @Override
  public Condition getDisposed() {
    return Condition.FALSE;
  }
}
