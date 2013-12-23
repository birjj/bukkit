package com.gmail.johanringmann.blockcounter;

import java.util.Collection;
import java.util.HashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.ChatColor;

/* TO DO */
/* - Implement save every X seconds */

public final class BlockCounter extends JavaPlugin implements Listener {
	// Config stuff
	private int saveDelay;
	private int vipFactor;
	private int payType;
	private HashMap<String, Integer> blockPay = new HashMap<String, Integer>();
	private int taskId;
	
	// MySQL variables
	private MySQL MySQL;
	private Connection c = null;
	private Statement st = null;
	
	// Stats variables
	// old[X] are used for failsafing against payouts in-between saves
	private HashMap<String, Integer> blocks = new HashMap<String, Integer>();
	private HashMap<String, Integer> oldBlocks = new HashMap<String, Integer>();
	private HashMap<String, Integer> paid = new HashMap<String, Integer>();
	private HashMap<String, BigDecimal> btc = new HashMap<String, BigDecimal>();
	private HashMap<String, BigDecimal> oldBtc = new HashMap<String, BigDecimal>();
	
	private HashMap<String, int[][]> history = new HashMap<String, int[][]>();

	
	// Called when the plugin is enabled
	@Override
	public void onEnable() {
		// Setup configuration
		loadConfig();
		
		// Register events
		getServer().getPluginManager().registerEvents(this, this);
		
		// Pull blockPay from config
		try {
			blockPay = new HashMap(this.getConfig().getConfigurationSection("blocks").getValues(false));
		} catch (Exception e) {
			getLogger().info("Failed at loading block pays. Are they integers?");
		}
		saveDelay = this.getConfig().getInt("saveDelay");
		vipFactor = this.getConfig().getInt("vipFactor");
		payType = this.getConfig().getInt("payoutType");
			
		// Open a connection to the database
		MySQL = new MySQL(this, this.getConfig().getString("SQL.host"), this.getConfig().getString("SQL.port"), this.getConfig().getString("SQL.database"), this.getConfig().getString("SQL.user"), this.getConfig().getString("SQL.pass"));
		c = MySQL.openConnection();
		
		try {
			st = c.createStatement();
		} catch (SQLException e) {
			getLogger().severe("Failed to create statement for MySQl connection.");
		} catch (NullPointerException e) {
			getLogger().severe("Failed to connect to MySQL database.");
		}
		
		// Setup every player
		for (Player p : this.getServer().getOnlinePlayers()) {
			setupPlayer(p);
		}
		
		taskId = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
			public void run() {
				saveAll();
			}}, saveDelay * 20, saveDelay * 20);
	}
	
	// Called when the plugin is disabled
	@Override
	public void onDisable() {
		// Save every players data
		saveAll();
	}
	
	// Called when a player/console uses the blockcount command
	public boolean onCommand(CommandSender sender, Command command, String cmdLabel, String[] args) {
		// If no parameters were passed
		if (args.length == 0) {
			// If the server called the command
			if (!(sender instanceof Player)) {
				getLogger().info("I currently have the following data saved:");
				for (String i : blocks.keySet()) {
					getLogger().info("Player: " + i);
					getLogger().info("  Blocks: " + blocks.get(i));
					getLogger().info("  oldBlocks: " + oldBlocks.get(i));
					getLogger().info("  BTC: " + btc.get(i).toPlainString());
					getLogger().info("  oldBTC: " + oldBtc.get(i).toPlainString());
				}
			// If a player called the command
			} else {
				Player p = (Player) sender;
				
				// Print the players stats
				messagePlayer(p, "Your stats are:");
				messagePlayer(p, "  Blocks: " + ChatColor.RESET + Integer.toString(blocks.get(p.getName())));
				messagePlayer(p, "  BTC: " + ChatColor.RESET + btc.get(p.getName()).toPlainString());
				messagePlayer(p, "  Paid blocks: " + ChatColor.RESET + Integer.toString(paid.get(p.getName())));
			}
		// If the player/console is searching for another player
		} else {
			String pName = args[0];
			// If we don't have values stored for the player
			String s1, s2, s3, s4;
			s1 = "Player stats for " + pName + ":";
			if (blocks.get(pName) == null) {
				s2 = "  Blocks: " + ChatColor.RESET + Integer.toString(getValue(pName, "total"));
				s3 = "  BTC: " + ChatColor.RESET + getBTC(pName).toPlainString();
				s4 = "  Paid blocks: " + ChatColor.RESET + Integer.toString(getValue(pName, "paid"));
			// If we already have them saved
			} else {
				s2 = "  Blocks: " + ChatColor.RESET + blocks.get(pName);
				s3 = "  BTC: " + ChatColor.RESET + btc.get(pName);
				s4 = "  Paid blocks: " + ChatColor.RESET + paid.get(pName);
			}
			
			if (!(sender instanceof Player)) {
				getLogger().info(s1);
				getLogger().info(s2);
				getLogger().info(s3);
				getLogger().info(s4);
			} else {
				messagePlayer((Player) sender, s1);
				messagePlayer((Player) sender, s2);
				messagePlayer((Player) sender, s3);
				messagePlayer((Player) sender, s4);
			}
		}
		
		return true;
	}
	
	// Someone broke a block
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBreak(BlockBreakEvent e) {
		Player p = e.getPlayer();
		Block b = e.getBlock();
		
		if (history.get(p.getName())==null) {
			// If this is the first block broken
			setupHistory(p, b.getX(), b.getY(), b.getZ());
			addBlock(p, b);
		} else {
			// If this block isn't in the history of this player
			if (!arrContains( history.get(p.getName()) , new int[]{b.getX(),b.getY(),b.getZ()} ) == true) {
				addBlock(p, b);
			}
			updateHistory(p, b.getX(), b.getY(), b.getZ());
		}
	}
	
	// Someone placed a block
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		Player p = e.getPlayer();
		Block b = e.getBlockPlaced();
		p.sendMessage("You placed a block.");
		
		if (history.get(p.getName())==null) {
			// If this is the first block broken
			setupHistory(p, b.getX(), b.getY(), b.getZ());
			addBlock(p, b);
		} else {
			// If this block isn't in the history of this player
			if (!arrContains( history.get(p.getName()) , new int[]{b.getX(),b.getY(),b.getZ()} ) == true) {
				addBlock(p, b);
			}
			updateHistory(p, b.getX(), b.getY(), b.getZ());
		}
	}
	
	// Someone succesfully connected
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onLogin(PlayerJoinEvent e) {
		setupPlayer(e.getPlayer());
	}
	
	// Someone disconnected
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onQuit(PlayerQuitEvent e) {
		savePlayer(e.getPlayer());
	}
	
	// Save every player
	public void saveAll() {
		// Save every players data
		for (Player p : this.getServer().getOnlinePlayers()) {
			p.sendMessage("Saving." + saveDelay);
			savePlayer(p);
		}
	}
	
	// Load the config (or create it)
	private void loadConfig() {
		// Create the blockPay hashmap
		for (Material m :  Material.values()) {
			if (m.isBlock()) {
				blockPay.put(m.name(), 20);
			}
		}
		this.getConfig().options().header("The BlockCounter configuration.\r\n"
				+ "SQL:\r\n"
				+ "  Simple data for connecting to MySQL.\r\n"
				+ "  Should be fairly self-explanatory\r\n"
				+ "\r\n"
				+ "saveDelay:\r\n"
				+ "  The amount of time to wait between auto-saves. In seconds.\r\n"
				+ "\r\n"
				+ "vipFactor:\r\n"
				+ "  The amount to duplicate payout with for people with blockcounter.vippay permission\r\n"
				+ "\r\n"
				+ "payoutType:\r\n"
				+ "  An integer that defines the way payouts is calculated.\r\n"
				+ "  0 - Flat payout. Uses the value of AIR for all blocks.\r\n"
				+ "  1 - Per-block payout. Uses each blocks own payout.\r\n"
				+ "\r\n"
				+ "blocks:\r\n"
				+ "  A list of all available blocks and their payouts. Counted in satoshi.");
		
		this.getConfig().addDefault("SQL.host", "localhost");
		this.getConfig().addDefault("SQL.port", "3306");
		this.getConfig().addDefault("SQL.database", "blockcounter");
		this.getConfig().addDefault("SQL.user", "bukkit");
		this.getConfig().addDefault("SQL.pass", "bukkitpass");
		
		this.getConfig().addDefault("saveDelay", 30);
		this.getConfig().addDefault("vipFactor", 2);
		this.getConfig().addDefault("payoutType", 0);
		
		this.getConfig().addDefault("blocks", blockPay);
		
		this.getConfig().options().copyDefaults(true);
		this.saveConfig();
	}
	
	// Add payout for a new block
	private void addBlock(Player p, Block b) {
		// Add to blocks hashmap
		blocks.put(p.getName(), blocks.get(p.getName()) + 1);
		
		// Add to btc hashmap
		BigDecimal deltBtc = new BigDecimal("0.000000001");
		
		if (payType == 0) {
			deltBtc = deltBtc.multiply(new BigDecimal(blockPay.get("AIR")));
		} else {
			deltBtc = deltBtc.multiply(new BigDecimal(blockPay.get(b.getType().name())));
		}
		
		// Multiply if player high enough rank
		if (p.hasPermission("blockcounter.vippay")) {
			deltBtc = deltBtc.multiply(new BigDecimal(vipFactor));
		}
		
		// Save
		btc.put(p.getName(), btc.get(p.getName()).add(deltBtc));
	}
	
	// Setup the history of blocks for a player
	private void setupHistory(Player player, int x, int y, int z) {
		// Setup two-dimensional array
		// Saves for 20 blocks back, with 3 values for each block
		int[][] hist = new int[20][3];
		hist[0][0] = x;
		hist[0][1] = y;
		hist[0][2] = z;
		
		// Save array
		history.put(player.getName(), hist);
	}
	
	// Add to history of blocks for a player
	private void updateHistory(Player player, int x, int y, int z) {
		int[][] hist = history.get(player.getName());
		
		// Move every previous block one step back
		for (int i = hist.length - 1; i > 0; i--) {
			System.arraycopy(hist[i-1], 0, hist[i], 0, 3);
		}
		
		// Set the new block
		hist[0][0] = x;
		hist[0][1] = y;
		hist[0][2] = z;
		
		// Save
		history.put(player.getName(),  hist);
	}
	
	// Setup all hashmaps for a new player
	private void setupPlayer(Player player) {
		// Get values from database
		int tmpBlocks = getValue(player.getName(), "total");
		BigDecimal tmpBtc = getBTC(player.getName());
		int tmpPaid = getValue(player.getName(), "paid");
		
		// Save in hashmaps
		blocks.put(player.getName(), tmpBlocks);
		oldBlocks.put(player.getName(), tmpBlocks);
		btc.put(player.getName(), tmpBtc);
		oldBtc.put(player.getName(), tmpBtc);
		paid.put(player.getName(), tmpPaid);
	}
	
	// Save to database for a given player
	private void savePlayer(Player player) {
		// Get values from hashmaps
		int tmpBlocks = blocks.get(player.getName());
		BigDecimal tmpBtc = btc.get(player.getName());
		int tmpPaid = paid.get(player.getName());
		
		int oBlocks = oldBlocks.get(player.getName());
		BigDecimal oBtc = oldBtc.get(player.getName());
		
		// Insert into database
		try {
			// Create query
			String q = "INSERT INTO players VALUES ('"+player.getName()
					+ "', "+Integer.toString(tmpBlocks)+", "+tmpBtc.toString()
					+ ", "+Integer.toString(tmpPaid)+")"
					+ " ON DUPLICATE KEY "
					+ "UPDATE total = if (total = "+Integer.toString(oBlocks)
					+ ", VALUES(total), total + "+(tmpBlocks-oBlocks)+"), "
					+ "btc = if (btc = "+oBtc.toPlainString()+", VALUES(btc)"
					+ ", btc + "+tmpBtc.subtract(oBtc).toPlainString()+");";
			
			getLogger().info("Saving using the following query:");
			getLogger().info(q);
					
			st.executeUpdate(q);
		} catch (SQLException e) {
			getLogger().severe("Failed to save a players stats!");
			getLogger().severe("Player: " + player.getName());
			getLogger().severe(e.toString());
		}
	}
	
	private int getValue(String name, String value) {
		try {
			// Retrieve the total block count from database
			ResultSet res = st.executeQuery("SELECT "+value+" FROM players WHERE name='"+name+"';");
			res.next();
			// return the value
			return res.getInt(value);
		} catch (SQLException e) {
			// Log the failure, and tell the player
			getLogger().warning("Failed to load a players "+value+" blocks.");
			getLogger().warning("Player: " + name);
			getLogger().warning(e.toString());
		}
		return 0;
	}
	
	//Due to needed precision, this needs to return a BigDecimal
	private BigDecimal getBTC(String name) {
		try {
			// Retrieve the total block count from database
			ResultSet res = st.executeQuery("SELECT btc FROM players WHERE name='"+name+"';");
			res.next();
			// return the value
			return res.getBigDecimal("btc").setScale(9, RoundingMode.HALF_UP);
		} catch (SQLException e) {
			// Log the failure, and tell the player
			getLogger().warning("Failed to load a players BTC balance.");
			getLogger().warning("Player: " + name);
			getLogger().warning(e.toString());
		}
		return new BigDecimal("0.000000000");
	}
	
	// Send a message to a player, with plugin formatting
	private void messagePlayer(Player player, String text) {
		
		player.sendMessage(ChatColor.BOLD + "" + ChatColor.GOLD + "[BlockCounter]" + ChatColor.RESET + " " + ChatColor.YELLOW + text);
	}
	
	// Search a two-dimensional array for another array
	private boolean arrContains(int[][] arr, int[] val) {
		for (int[] subArr : arr) {
			if (subArr[0]==val[0] && subArr[1]==val[1] && subArr[2]==val[2]) {
				return true;
			}
		}
		
		return false;
	}
}
