package me.parapenguin.overcast.scrimmage;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import lombok.Getter;
import lombok.Setter;
import me.parapenguin.overcast.scrimmage.event.PlayerEvents;
import me.parapenguin.overcast.scrimmage.map.Map;
import me.parapenguin.overcast.scrimmage.map.MapLoader;
import me.parapenguin.overcast.scrimmage.map.MapTeam;
import me.parapenguin.overcast.scrimmage.map.filter.FilterEvents;
import me.parapenguin.overcast.scrimmage.map.objective.ObjectiveEvents;
import me.parapenguin.overcast.scrimmage.map.region.Region;
import me.parapenguin.overcast.scrimmage.player.Client;
import me.parapenguin.overcast.scrimmage.player.commands.*;
import me.parapenguin.overcast.scrimmage.rotation.Rotation;
import me.parapenguin.overcast.scrimmage.tracker.GravityKillTracker;
import me.parapenguin.overcast.scrimmage.tracker.PlayerBlockChecker;
import me.parapenguin.overcast.scrimmage.tracker.TickTimer;
import me.parapenguin.overcast.scrimmage.utils.FileUtil;
import me.parapenguin.overcast.scrimmage.utils.JarUtils;
import me.parapenguin.overcast.scrimmage.utils.ZipUtil;


public class Scrimmage extends JavaPlugin {
	
	static @Getter Scrimmage instance;
	static @Getter @Setter Rotation rotation;
	@Getter List<File> libs = new ArrayList<File>();
	@Getter List<String> files = new ArrayList<String>();

	private TickTimer tickTimer;
	@Getter public GravityKillTracker gkt;
	
	static @Getter String team;
	static @Getter @Setter boolean open;
	
	private @Getter @Setter File rootDirectory;
	private @Getter @Setter String mapDirectory;
	
	@Getter public static double MINIMUM_MOVEMENT = 0.125;	
	
	public void onEnable() {
		
		reloadConfig();
		setOpen(false);
		instance = this;
		Region.MAX_BUILD_HEIGHT = 256;
		
		this.rootDirectory = getServer().getWorldContainer();
		if(getConfig().getString("maps") != null)
			this.mapDirectory = getConfig().getString("maps");
		
		files = new ArrayList<String>();
		File libFolder = new File(getRootDirectory(), "libs");
		if(!libFolder.exists()) libFolder = getDataFolder().getParentFile().getParentFile().getParentFile().getParentFile();
		files.add("dom4j.jar");
		
		for (String stringFile : files) {

			if (libFolder.exists() && libFolder.isDirectory()) {
				libs.add(new File(libFolder.getAbsolutePath() + "/" + stringFile));
			} else if (!libFolder.exists()) {
				libFolder.mkdir();
				libs.add(new File(libFolder.getAbsolutePath() + "/" + stringFile));
			} else {
				getLogger().warning("/" + libFolder.getParentFile().getName() + "/" + libFolder.getName() + " already exists and isn't a directory.");
				Bukkit.getServer().getPluginManager().disablePlugin(this);
			}
		}

		loadJars();
		
		/*
		 * Auto un-zipper, this should be helpful instead of killing my internet :)
		 */
		
		File[] maps = getMapRoot().listFiles();
		File zips = new File(getMapRoot().getAbsolutePath() + "/zips/");
		
		for(File file : maps) {
			if(!file.getName().toLowerCase().contains(".zip"))
				continue;
			
			if(!zips.isDirectory())
				FileUtil.delete(zips);
			if(!zips.exists())
				zips.mkdirs();
			
			ZipUtil.unZip(file, getMapRoot());
			try {
				FileUtil.move(file, new File(zips.getAbsolutePath() + "/" + file.getName()));
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	public void startup() {
		team = getConfig().getString("team");
		if(team == null)
			team = "public";
		Scrimmage.getInstance().getConfig().set("team", team);
		Scrimmage.getInstance().saveConfig();
		
		// Load the maps from the local map repository (no github/download connections this time Harry...)
		File[] files = getMapRoot().listFiles();
		
		for(File file : files)
			if(file.isDirectory())
				for(File contains : file.listFiles())
					if(!contains.isDirectory() && contains.getName().endsWith(".xml") && MapLoader.isLoadable(contains)) {
						MapLoader loader = MapLoader.getLoader(contains);
						Rotation.addMap(loader);
					}
		
		setRotation(new Rotation());
		registerListener(new PlayerEvents());
		registerListener(new FilterEvents());
		registerListener(new ObjectiveEvents());
		getRotation().start();
		
		registerCommand("join", new JoinCommand());
		registerCommand("setteam", new SetTeamCommand());
		registerCommand("setnext", new SetNextCommand());
		registerCommand("global", new GlobalCommand());
		registerCommand("start", new StartCommand());
		registerCommand("cycle", new CycleCommand());
		registerCommand("end", new StopCommand());
		registerCommand("a", new AdminChat());
		registerCommand("staff", new StaffCommand());
		registerCommand("help", new HelpCommand());
		registerCommand("stoptheserver", new StopTheServer());
		registerCommand("request", new RequestCommand());
		registerCommand("force", new ForceCommand());
		registerCommand("match", new MatchCommand());
		registerCommand("test", new TestCommand());
		enableTracker();
	}

	public void enableTracker() {
		tickTimer = new TickTimer(this);
		tickTimer.start();

		gkt = new GravityKillTracker(tickTimer, new PlayerBlockChecker());
		getServer().getPluginManager().registerEvents(gkt, this);
		getServer().getPluginManager().registerEvents(tickTimer, this);
	}
	
	public void loadJars() {
		/*
		 * That awkward moment when you forget to upload the jar file... hahah!
		 */
		
		new BukkitRunnable() {
			
			@Override
			public void run() {
				for (File lib : libs) {
					try {
						addClassPath(JarUtils.getJarUrl(lib));
						getLogger().info("'" + lib.getName() + "' has been loaded!");
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				
				startup();
			}
			
		}.runTask(instance);
	}
	
	public void addClassPath(final URL url) throws IOException {
		final URLClassLoader sysloader = (URLClassLoader) ClassLoader.getSystemClassLoader();
		final Class<URLClassLoader> sysclass = URLClassLoader.class;
		try {
			final Method method = sysclass.getDeclaredMethod("addURL", new Class[] { URL.class });
			method.setAccessible(true);
			method.invoke(sysloader, new Object[] { url });
		} catch (final Throwable t) {
			t.printStackTrace();
			throw new IOException("Error adding " + url + " to system classloader");
		}
	}
	
	public static int random(int min, int max) {
		return (int) (min + (Math.random() * (max - min)));
	}
	
	public static File getRootFolder() {
		return instance.getRootDirectory();
	}
	
	public static File getMapRoot() {
		if(getInstance().getMapDirectory() != null)
			return new File(getInstance().getMapDirectory());
		else return new File(getRootFolder().getAbsolutePath() + "/maps/");
	}
	
	public static void broadcast(String message) {
		getInstance().getServer().broadcastMessage(message);
	}
	
	public static void debug(String message, String type) {
		getInstance().getServer().broadcastMessage(ChatColor.RED + "[DEBUG " + type + "]: " + ChatColor.GREEN + message);
	}
	
	public static void broadcast(String message, MapTeam team) {
		if(team == null)
			getInstance().getServer().broadcastMessage(message);
		else
			for(Client client : team.getPlayers())
				client.getPlayer().sendMessage(message);
	}
	
	public static void registerCommand(String label, CommandExecutor cmdEx) {
		getInstance().getCommand(label).setExecutor(cmdEx);
	}
	
	public static void registerListener(Listener listener) {
		getInstance().getServer().getPluginManager().registerEvents(listener, getInstance());
	}
	
	public static void callEvent(Event event) {
		getInstance().getServer().getPluginManager().callEvent(event);
	}
	
	public static boolean isPublic() {
		return getTeam().equalsIgnoreCase("public");
	}
	
	public static int getID() {
		return getInstance().getServer().getPort() - 25560;
	}
	
	public static Map getMap() {
		return Scrimmage.getRotation().getSlot().getMap();
	}	
}
