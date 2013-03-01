package org.jboss.forge.container;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

import org.jboss.forge.container.exception.ContainerException;
import org.jboss.forge.container.impl.AddonRegistryImpl;
import org.jboss.forge.container.impl.AddonRepositoryImpl;
import org.jboss.forge.container.modules.AddonModuleLoader;
import org.jboss.modules.Module;
import org.jboss.modules.log.StreamModuleLogger;

public class ForgeImpl implements Forge
{
   private static Logger logger = Logger.getLogger(ForgeImpl.class.getName());

   private volatile boolean alive = false;
   private boolean serverMode = true;
   private AddonRepository repository = AddonRepositoryImpl.forDefaultDirectory();
   private AddonRegistryImpl registry = new AddonRegistryImpl(this);

   public ForgeImpl()
   {
      if (!AddonRepositoryImpl.hasRuntimeAPIVersion())
         logger.warning("Could not detect Forge runtime version - " +
                  "loading all addons, but failures may occur if versions are not compatible.");

   }

   public Forge enableLogging()
   {
      Module.setModuleLogger(new StreamModuleLogger(System.err));
      return this;
   }

   @Override
   public Forge startAsync()
   {
      return startAsync(Thread.currentThread().getContextClassLoader());
   }

   @Override
   public Forge startAsync(final ClassLoader loader)
   {
      new Thread()
      {
         @Override
         public void run()
         {
            ForgeImpl.this.start(loader);
         };
      }.start();

      return this;
   }

   @Override
   public Forge start()
   {
      return start(Thread.currentThread().getContextClassLoader());
   }

   @Override
   public Forge start(ClassLoader loader)
   {
      if (!alive)
      {
         try
         {
            AddonModuleLoader moduleLoader = new AddonModuleLoader(repository, loader);
            registry.setAddonLoader(moduleLoader);
            alive = true;
            Set<Future<Addon>> futures = new HashSet<Future<Addon>>();
            do
            {
               futures.addAll(registry.startAll());
               Thread.sleep(100);
            }
            while (alive == true && (serverMode || isStartingAddons(futures)));
         }
         catch (InterruptedException e)
         {
            throw new ContainerException(e);
         }
         finally
         {
            registry.stopAll();
         }
      }
      return this;
   }

   private boolean isStartingAddons(Set<Future<Addon>> futures)
   {
      for (Future<Addon> future : futures)
      {
         try
         {
            future.get(0, TimeUnit.MILLISECONDS);
         }
         catch (TimeoutException e)
         {
            return true;
         }
         catch (Exception e)
         {
            throw new ContainerException(e);
         }
      }
      return false;
   }

   @Override
   public Forge stop()
   {
      alive = false;
      return this;
   }

   @Override
   public Forge setAddonDir(File dir)
   {
      this.repository = AddonRepositoryImpl.forDirectory(dir);
      return this;
   }

   @Override
   public Forge setServerMode(boolean server)
   {
      this.serverMode = server;
      return this;
   }

   @Override
   public File getAddonDir()
   {
      return repository.getRepositoryDirectory();
   }

   @Override
   public AddonRegistry getAddonRegistry()
   {
      return registry;
   }

   @Override
   public AddonRepository getRepository()
   {
      return repository;
   }

   @Override
   public String getVersion()
   {
      return AddonRepositoryImpl.getRuntimeAPIVersion();
   }
}