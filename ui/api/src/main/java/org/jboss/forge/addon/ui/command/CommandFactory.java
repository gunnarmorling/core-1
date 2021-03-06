/*
 * Copyright 2014 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.jboss.forge.addon.ui.command;

/**
 * Responsible for producing {@link UICommand} objects from all registered {@link CommandProvider} instances.
 * 
 * @author <a href="mailto:lincolnbaxter@gmail.com">Lincoln Baxter, III</a>
 */
public interface CommandFactory
{
   /**
    * Get all {@link UICommand} instances from all available {@link CommandProvider} services.
    */
   Iterable<UICommand> getCommands();
}
