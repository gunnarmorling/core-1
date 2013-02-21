package org.jboss.forge.projects.impl;

import java.io.File;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import org.jboss.forge.projects.ProjectType;
import org.jboss.forge.resource.DirectoryResource;
import org.jboss.forge.resource.FileResource;
import org.jboss.forge.resource.Resource;
import org.jboss.forge.resource.ResourceFactory;
import org.jboss.forge.ui.UIBuilder;
import org.jboss.forge.ui.UICommand;
import org.jboss.forge.ui.UICommandMetadata;
import org.jboss.forge.ui.base.UICommandMetadataBase;
import org.jboss.forge.ui.context.UIContext;
import org.jboss.forge.ui.context.UIValidationContext;
import org.jboss.forge.ui.input.UIInput;
import org.jboss.forge.ui.input.UISelection;
import org.jboss.forge.ui.result.Result;
import org.jboss.forge.ui.result.Results;
import org.jboss.forge.ui.util.Categories;

public class NewProjectCommand implements UICommand
{
   @Inject
   private ResourceFactory factory;

   @Inject
   private UIInput<String> named;

   @Inject
   private UIInput<DirectoryResource> targetLocation;

   @Inject
   private UIInput<Boolean> overwrite;

   @Inject
   private UIInput<ProjectType> type;

   @Override
   public UICommandMetadata getMetadata()
   {
      return new UICommandMetadataBase("New Project", "Create a new project",
               Categories.create("Project", "Generation"));
   }

   @Override
   public boolean isEnabled(UIContext context)
   {
      return true;
   }

   @Override
   public void initializeUI(final UIBuilder builder) throws Exception
   {

      named.setLabel("Project name");
      named.setRequired(true);

      targetLocation.setLabel("Project location");

      UISelection<Resource<?>> currentSelection = builder.getUIContext().getInitialSelection();
      if (currentSelection != null)
      {
         Resource<?> resource = currentSelection.get();
         if (resource instanceof DirectoryResource)
         {
            targetLocation.setDefaultValue((DirectoryResource) resource);
         }
      }
      else
      {
         targetLocation.setDefaultValue(factory.create(DirectoryResource.class, new File("")));
      }
      overwrite.setLabel("Overwrite existing project location");
      overwrite.setDefaultValue(false).setEnabled(new Callable<Boolean>()
      {
         @Override
         public Boolean call() throws Exception
         {
            return targetLocation.getValue() != null
                     && targetLocation.getValue().exists()
                     && !targetLocation.getValue().listResources().isEmpty();
         }
      });

      type.setRequired(false);

      builder.add(named).add(targetLocation).add(overwrite).add(type);
   }

   @Override
   public void validate(UIValidationContext context)
   {
      if (overwrite.isEnabled() && overwrite.getValue() == false)
      {
         context.addValidationError(targetLocation, "Target location is not empty.");
      }

   }

   @Override
   public Result execute(UIContext context) throws Exception
   {
      DirectoryResource directory = targetLocation.getValue();
      DirectoryResource targetDir = directory.getChildDirectory(named.getValue());

      if (targetDir.mkdirs() || overwrite.getValue())
      {
         FileResource<?> pom = targetDir.getChild("pom.xml").reify(FileResource.class);
         pom.createNewFile();
         pom.setContents(getClass().getClassLoader().getResourceAsStream("/pom-template.xml"));

         targetDir.getChildDirectory("src/main/java").mkdirs();
         targetDir.getChildDirectory("src/main/resources").mkdirs();
         targetDir.getChildDirectory("src/test/java").mkdirs();
         targetDir.getChildDirectory("src/test/resources").mkdirs();
      }
      else
         return Results.fail("Could not create target location: " + targetDir);

      return Results.success("New project has been created.");
   }

   public UIInput<String> getNamed()
   {
      return named;
   }

   public UIInput<DirectoryResource> getTargetLocation()
   {
      return targetLocation;
   }

   public UIInput<Boolean> getOverwrite()
   {
      return overwrite;
   }

   public UIInput<ProjectType> getType()
   {
      return type;
   }
}