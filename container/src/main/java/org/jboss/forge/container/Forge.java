package org.jboss.forge.container;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.forge.container.exception.ContainerException;
import org.jboss.forge.container.impl.AddonRegistryImpl;
import org.jboss.forge.container.impl.AddonRepositoryImpl;
import org.jboss.forge.container.impl.RegisteredAddonImpl;
import org.jboss.forge.container.modules.AddonModuleLoader;
import org.jboss.forge.container.util.Sets;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoader;
import org.jboss.modules.log.StreamModuleLogger;

public final class Forge
{
   private static final String PROP_CONCURRENT_PLUGINS = "forge.concurrentAddons";

   private static final int BATCH_SIZE = Integer.getInteger(PROP_CONCURRENT_PLUGINS, 4);

   private Logger logger = Logger.getLogger(getClass().getName());

   private volatile boolean alive = false;

   private AddonRepository repository = AddonRepositoryImpl.forDefaultDirectory();

   Set<AddonThread> threads = Sets.getConcurrentSet();

   public Forge()
   {
      if (!AddonRepositoryImpl.hasRuntimeAPIVersion())
         logger.warning("Could not detect Forge runtime version - " +
                  "loading all addons, but failures may occur if versions are not compatible.");
      installMBeanServer();
   }

   private void installMBeanServer()
   {
      try
      {
         Method method = ModuleLoader.class.getDeclaredMethod("installMBeanServer");
         method.setAccessible(true);
         method.invoke(null);
      }
      catch (Exception e)
      {
         throw new ContainerException("Could not install Modules MBean server", e);
      }
   }

   public Forge enableLogging()
   {
      Module.setModuleLogger(new StreamModuleLogger(System.err));
      // if (LOGGING_ENABLED)
      // initLogging();
      return this;
   }

   public Set<AddonThread> getThreads()
   {
      return threads;
   }

   public Forge start()
   {
      if (!alive)
      {
         try
         {
            ModuleLoader addonLoader = new AddonModuleLoader(repository);
            alive = true;
            do
            {
               updateAddons(threads, addonLoader);
               Thread.sleep(100);
            }
            while (alive == true);
         }
         catch (InterruptedException e)
         {
            throw new ContainerException(e);
         }
      }
      return this;
   }

   public Forge stop()
   {
      alive = false;
      return this;
   }

   private void updateAddons(Set<AddonThread> threads, ModuleLoader addonLoader)
   {
      Set<RegisteredAddon> loadedAddons = new HashSet<RegisteredAddon>();
      for (AddonThread thread : threads)
      {
         loadedAddons.add(thread.getRunnable().getAddon());
      }

      Set<RegisteredAddon> toStop = new HashSet<RegisteredAddon>(loadedAddons);
      Set<RegisteredAddon> updatedSet = loadAddons(addonLoader);
      toStop.removeAll(updatedSet);

      Set<RegisteredAddon> toStart = new HashSet<RegisteredAddon>(updatedSet);
      toStart.removeAll(loadedAddons);

      if (!toStop.isEmpty())
      {
         Set<AddonThread> stopped = new HashSet<AddonThread>();
         for (RegisteredAddon addon : toStop)
         {
            // TODO This needs to handle dependencies and ordering.
            ((RegisteredAddonImpl) addon).setStatus(Status.STOPPING);
            logger.info("Stopping addon (" + addon.getId() + ")");
            for (AddonThread thread : threads)
            {
               if (addon.equals(thread.getRunnable().getAddon()))
               {
                  thread.getRunnable().shutdown();
                  stopped.add(thread);
                  AddonRegistryImpl.INSTANCE.remove(addon);
               }
            }
         }
         threads.removeAll(stopped);
      }

      if (!toStart.isEmpty())
      {
         Set<AddonThread> started = startAddons(toStart);
         threads.addAll(started);
      }
   }

   private Set<AddonThread> startAddons(Set<RegisteredAddon> toStart)
   {
      Set<AddonThread> started = new HashSet<AddonThread>();
      AddonRegistryImpl registry = AddonRegistryImpl.INSTANCE;

      int startedThreads = 0;
      int batchSize = Math.min(BATCH_SIZE, toStart.size());
      for (RegisteredAddon addon : toStart)
      {
         ((RegisteredAddonImpl) addon).setStatus(Status.STARTING);
         logger.info("Starting addon (" + addon.getId() + ")");
         while (registry.getServices().size() + batchSize <= startedThreads)
         {
            try
            {
               Thread.sleep(10);
            }
            catch (InterruptedException e)
            {
               throw new ContainerException("Thread interrupted while waiting for an executor.", e);
            }
         }

         AddonRunnable runnable = new AddonRunnable(this, (RegisteredAddonImpl) addon);
         Thread thread = new Thread(runnable, addon.getId().toCoordinates());
         started.add(new AddonThread(thread, runnable));
         thread.start();

         startedThreads++;
      }
      return started;
   }

   public AddonRegistry getAddonRegistry()
   {
      return AddonRegistryImpl.INSTANCE;
   }

   synchronized private Set<RegisteredAddon> loadAddons(ModuleLoader addonLoader)
   {
      Set<RegisteredAddon> result = new HashSet<RegisteredAddon>();

      String runtimeVersion = AddonRepositoryImpl.getRuntimeAPIVersion();
      List<AddonId> enabledCompatible = repository.listEnabledCompatibleWithVersion(runtimeVersion);

      if (AddonRepositoryImpl.hasRuntimeAPIVersion())
      {
         List<AddonId> incompatible = repository.listEnabled();
         incompatible.removeAll(enabledCompatible);

         for (AddonId entry : incompatible)
         {
            logger.info("Not loading addon [" + entry.getName()
                     + "] because it references Forge API version [" + entry.getApiVersion()
                     + "] which may not be compatible with my current version ["
                     + AddonRepositoryImpl.getRuntimeAPIVersion() + "].");
         }
      }

      for (AddonId entry : enabledCompatible)
      {
         loadAddon(addonLoader, result, entry);
      }

      return result;
   }

   private RegisteredAddon loadAddon(ModuleLoader addonLoader, Set<RegisteredAddon> result, AddonId addonId)
   {
      AddonRegistryImpl registry = AddonRegistryImpl.INSTANCE;
      Map<RegisteredAddon, Set<RegisteredAddon>> waitlist = registry.getMutableWaitlist();

      RegisteredAddonImpl addonToLoad = (RegisteredAddonImpl) registry.getRegisteredAddon(addonId);
      if (addonToLoad == null)
      {
         addonToLoad = new RegisteredAddonImpl(addonId);
         registry.register(addonToLoad);
      }

      if (!(repository.isDeployed(addonId) && repository.isEnabled(addonId)))
      {
         addonToLoad.setStatus(Status.FAILED);
      }
      else
      {
         if (!registry.isWaiting(addonToLoad))
         {
            Set<RegisteredAddon> missingDependencies = new HashSet<RegisteredAddon>();
            for (AddonDependency dependency : repository.getAddonDependencies(addonId))
            {
               RegisteredAddon registeredDependency;

               AddonId dependencyId = dependency.getId();
               if (!registry.isRegistered(dependencyId))
               {
                  if (repository.isDeployed(dependencyId) && repository.isEnabled(dependencyId))
                  {
                     if (!Status.STARTED.equals(registry.getRegisteredAddon(dependencyId).getStatus()))
                     {
                        registeredDependency = loadAddon(addonLoader, result, dependencyId);
                        if (Status.FAILED.equals(registeredDependency.getStatus()))
                        {
                           if (dependency.isOptional())
                              logger.log(Level.WARNING, "Could not load optional dependency: " + dependency
                                       + ", module will be restarted if dependency becomes available.");
                           else
                              missingDependencies.add(registeredDependency);
                        }
                     }
                  }
                  else
                  {
                     registeredDependency = new RegisteredAddonImpl(dependencyId).setStatus(Status.STOPPED);
                     registry.register(registeredDependency);
                     missingDependencies.add(registeredDependency);
                  }
               }
            }

            if (!missingDependencies.isEmpty())
            {
               addonToLoad.setStatus(Status.FAILED);
               waitlist.put(addonToLoad, missingDependencies); // overwrite existing missing deps with new ones
               logger.warning("Addon [" + addonToLoad + "] has [" + missingDependencies.size()
                        + "] missing dependencies: "
                        + missingDependencies + " and will be added to the waitlisted until all required"
                        + " missing dependencies are available.");
            }
            else
            {
               try
               {
                  Module module = addonLoader.loadModule(ModuleIdentifier.fromString(addonId.toModuleId()));
                  addonToLoad.setModule(module);
                  result.add(addonToLoad);

                  for (RegisteredAddon waiting : waitlist.keySet())
                  {
                     Set<RegisteredAddon> dependencies = waitlist.get(waiting);
                     if (dependencies.remove(addonToLoad) && dependencies.isEmpty())
                     {
                        ((RegisteredAddonImpl) waiting).setStatus(Status.STOPPED);
                     }
                  }
               }
               catch (Exception e)
               {
                  addonToLoad.setStatus(Status.FAILED);
               }
            }
         }
      }

      return addonToLoad;
   }

   public Forge setAddonDir(File dir)
   {
      this.repository = AddonRepositoryImpl.forDirectory(dir);
      return this;
   }

   public File getAddonDir()
   {
      return repository.getRepositoryDirectory();
   }

   public AddonRepository getRepository()
   {
      return repository;
   }

   public String getVersion()
   {
      return AddonRepositoryImpl.getRuntimeAPIVersion();
   }

}
