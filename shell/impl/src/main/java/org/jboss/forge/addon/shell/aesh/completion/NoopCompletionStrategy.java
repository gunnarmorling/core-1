/**
 * Copyright 2013 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.addon.shell.aesh.completion;

import org.jboss.aesh.complete.CompleteOperation;
import org.jboss.forge.addon.convert.ConverterFactory;
import org.jboss.forge.addon.shell.ui.ShellContext;
import org.jboss.forge.addon.ui.input.InputComponent;

/**
 * 
 * @author <a href="ggastald@redhat.com">George Gastaldi</a>
 */
enum NoopCompletionStrategy implements CompletionStrategy
{
   INSTANCE;
   
   @Override
   public void complete(CompleteOperation completeOperation, InputComponent<?, Object> input, ShellContext context,
            String typedValue, ConverterFactory converterFactory)
   {
      // do nothing
   }
}
