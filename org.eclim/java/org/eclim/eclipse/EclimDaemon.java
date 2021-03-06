/**
 * Copyright (C) 2012  Eric Van Dewoestine
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.eclim.eclipse;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import java.net.BindException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;

import java.util.ArrayList;
import java.util.List;

import org.eclim.Services;

import org.eclim.command.ReloadCommand;

import org.eclim.logging.Logger;

import org.eclim.plugin.PluginResources;

import org.eclim.util.IOUtils;

import org.eclim.util.file.FileUtils;

import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.core.runtime.Platform;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;

import com.martiansoftware.nailgun.NGServer;

/**
 * Class which handles starting/stopping of the eclim nailgun daemon along with
 * the loading/unloading of eclim plugins.
 *
 * @author Eric Van Dewoestine
 */
public class EclimDaemon
  implements FrameworkListener
{
  private static final Logger logger =
    Logger.getLogger(EclimDaemon.class);

  private static String CORE = "org.eclim.core";
  private static EclimDaemon instance = new EclimDaemon();

  private boolean started;
  private boolean starting;
  private boolean stopping;
  private NGServer server;

  private EclimDaemon()
  {
  }

  public static EclimDaemon getInstance()
  {
    return instance;
  }

  public void start()
  {
    if (started || starting){
        return;
    }

    String workspace = ResourcesPlugin
      .getWorkspace().getRoot().getRawLocation().toOSString().replace('\\', '/');
    logger.info("Workspace: " + workspace);

    starting = true;
    logger.info("Starting eclim...");

    final String host = Services.getPluginResources("org.eclim")
      .getProperty("nailgun.server.host");
    final String portString = Services.getPluginResources("org.eclim")
      .getProperty("nailgun.server.port");
    try{
      final int port = Integer.parseInt(portString);

      registerInstance(workspace, port);
      InetAddress address = InetAddress.getByName(host);
      server = new NGServer(address, port, getExtensionClassLoader());
      server.setCaptureSystemStreams(false);

      logger.info("Loading plugin org.eclim");
      PluginResources defaultResources = Services.getPluginResources("org.eclim");
      defaultResources.registerCommand(ReloadCommand.class);

      logger.info("Loading plugin org.eclim.core");

      Bundle bundle = Platform.getBundle(CORE);
      if(bundle == null){
        String diagnosis = EclimPlugin.getDefault().diagnose(CORE);
        logger.error(Services.getMessage("plugin.load.failed", CORE, diagnosis));
        return;
      }

      bundle.start(Bundle.START_TRANSIENT);
      bundle.getBundleContext().addFrameworkListener(this);

      // wait up to 10 seconds for bundles to activate.
      synchronized(this){
        wait(10000);
      }

      starting = false;
      started = true;
      logger.info("Eclim Server Started on: {}:{}", host, port);
      server.run();
    }catch(NumberFormatException nfe){
      logger.error("Error starting eclim:",
          new RuntimeException("Invalid port number: '" + portString + "'"));
    }catch(BindException be){
      logger.error("Error starting eclim on {}:{}", host, portString, be);
    }catch(Throwable t){
      logger.error("Error starting eclim:", t);
    }finally{
      starting = false;
      started = false;
    }
  }

  public void stop()
    throws Exception
  {
    if(started && !stopping){
      try{
        stopping = true;
        logger.info("Shutting down eclim...");

        if(server != null && server.isRunning()){
          server.shutdown(false /* exit vm */);
        }

        unregisterInstance();

        logger.info("Stopping plugin " + CORE);
        Bundle bundle = Platform.getBundle(CORE);
        if (bundle != null){
          try{
            bundle.stop();
          }catch(IllegalStateException ise){
            // thrown because eclipse osgi BaseStorage attempt to register a
            // shutdown hook during shutdown.
          }
        }

        logger.info("Eclim stopped.");
      }finally{
        stopping = false;
      }
    }
  }

  /**
   * Register the current instance in the eclimd instances file for use by vim.
   */
  private void registerInstance(String workspace, int port)
    throws Exception
  {
    File instances = new File(FileUtils.concat(
          System.getProperty("user.home"), ".eclim/.eclimd_instances"));

    FileOutputStream out = null;
    try{
      List<String> entries = readInstances();
      if (entries != null){
        String instance = workspace + ':' + port;
        if (!entries.contains(instance)){
          entries.add(0, instance);
          out = new FileOutputStream(instances);
          IOUtils.writeLines(entries, out);
        }
      }
    }catch(IOException ioe){
      logger.error(
          "\nError writing to eclimd instances file: " + ioe.getMessage() +
          "\n" + instances);
    }finally{
      IOUtils.closeQuietly(out);
    }
  }

  /**
   * Unregister the current instance in the eclimd instances file for use by vim.
   */
  private void unregisterInstance()
    throws Exception
  {
    File instances = new File(FileUtils.concat(
          System.getProperty("user.home"), ".eclim/.eclimd_instances"));

    FileOutputStream out = null;
    try{
      List<String> entries = readInstances();
      if (entries == null){
        return;
      }

      String workspace = ResourcesPlugin
        .getWorkspace().getRoot().getRawLocation().toOSString().replace('\\', '/');
      String port = Services.getPluginResources("org.eclim")
        .getProperty("nailgun.server.port");
      String instance = workspace + ':' + port;
      entries.remove(instance);

      if (entries.size() == 0){
        if (!instances.delete()){
          logger.error("Error deleting eclimd instances file: " + instances);
        }
      }else{
        out = new FileOutputStream(instances);
        IOUtils.writeLines(entries, out);
      }
    }catch(IOException ioe){
      logger.error(
          "\nError writing to eclimd instances file: " + ioe.getMessage() +
          "\n" + instances);
      return;
    }finally{
      IOUtils.closeQuietly(out);
    }
  }

  private List<String> readInstances()
    throws Exception
  {
    File doteclim =
      new File(FileUtils.concat(System.getProperty("user.home"), ".eclim"));
    if (!doteclim.exists()){
      if (!doteclim.mkdirs()){
        logger.error("Error creating ~/.eclim directory: " + doteclim);
        return null;
      }
    }
    File instances = new File(FileUtils.concat(
          doteclim.getAbsolutePath(), ".eclimd_instances"));
    if (!instances.exists()){
      try{
        instances.createNewFile();
      }catch(IOException ioe){
        logger.error(
            "\nError creating eclimd instances file: " + ioe.getMessage() +
            "\n" + instances);
        return null;
      }
    }

    FileInputStream in = null;
    try{
      in = new FileInputStream(instances);
      List<String> entries = IOUtils.readLines(in);
      return entries;
    }finally{
      IOUtils.closeQuietly(in);
    }
  }

  /**
   * Builds the classloader used for third party nailgun extensions dropped into
   * eclim's ext dir.
   *
   * @return The classloader.
   */
  private ClassLoader getExtensionClassLoader()
    throws Exception
  {
    File extdir = new File(FileUtils.concat(Services.DOT_ECLIM, "resources/ext"));
    if (extdir.exists()){
      FileFilter filter = new FileFilter(){
        public boolean accept(File file){
          return file.isDirectory() || file.getName().endsWith(".jar");
        }
      };

      ArrayList<URL> urls = new ArrayList<URL>();
      listFileUrls(extdir, filter, urls);
      return new URLClassLoader(
          urls.toArray(new URL[urls.size()]),
          this.getClass().getClassLoader());
    }
    return null;
  }

  private void listFileUrls(File dir, FileFilter filter, ArrayList<URL> results)
    throws Exception
  {
    File[] files = dir.listFiles(filter);
    for (File file : files) {
      if(file.isFile()){
        results.add(file.toURI().toURL());
      }else{
        listFileUrls(file, filter, results);
      }
    }
  }

  @Override
  public synchronized void frameworkEvent(FrameworkEvent event)
  {
    // We are using a framework INFO event to announce when all the eclim
    // plugins bundles have been started (but not necessarily activated yet).
    Bundle bundle = event.getBundle();
    if (event.getType() == FrameworkEvent.INFO &&
        CORE.equals(bundle.getSymbolicName()))
    {
      logger.info("Loaded plugin org.eclim.core");
      notify();
    }
  }
}
