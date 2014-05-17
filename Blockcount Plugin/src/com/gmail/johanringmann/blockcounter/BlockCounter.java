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
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.scoreboard.*;
import org.bukkit.OfflinePlayer;

public final class BlockCounter extends JavaPlugin implements Listener {
	// Config stuff
	private int saveDelay;
	private int vipFactor;
	private int payType;
	private int debugging;
	private HashMap<String, Integer> blockPay = new HashMap<String, Integer>();
	private HashMap<String, Integer> worldPays = new HashMap<String, Integer>();
	private int taskId;
	
	// MySQL variables
	private MySQL MySQL;
	private Connection c = null;
	private Statement st = null;
	
	// GUI stuff
	private ScoreboardManager scrManager;
	private HashMap<String, Scoreboard> scrBoards = new HashMap<String, Scoreboard>();
	private OfflinePlayer blocksPly;
	private OfflinePlayer satPly;
	private HashMap<String, Boolean> showScr = new HashMap<String, Boolean>();
	
	// Stats variables
	// old[X] are used for failsafe-ing against payouts in-between saves
	private HashMap<String, Integer> blocks = new HashMap<String, Integer>();
	private HashMap<String, Integer> oldBlocks = new HashMap<String, Integer>();
	private HashMap<String, BigDecimal> paid = new HashMap<String, BigDecimal>();
	private HashMap<String, BigDecimal> btc = new HashMap<String, BigDecimal>();
	private HashMap<String, BigDecimal> oldBtc = new HashMap<String, BigDecimal>();
	
	private HashMap<String, int[][]> history = new HashMap<String, int[][]>();

	
	// Called when the plugin is enabled
	@Override
	public void onEnable() {		
		scrManager = Bukkit.getScoreboardManager();
		blocksPly = Bukkit.getOfflinePlayer(ChatColor.YELLOW+"Blocks:");
		satPly = Bukkit.getOfflinePlayer(ChatColor.YELLOW+"Satoshi:");
		
		// Set example worldPays
		HashMap<String, HashMap<String, Integer>> tmp2 = new HashMap<String, HashMap<String, Integer>>();
		HashMap<String, Integer> tmp = new HashMap<String, Integer>();
		tmp.put("payoutType", 3);
		tmp2.put("world1", tmp);
		tmp = new HashMap<String, Integer>();
		tmp.put("STONE", 0);
		tmp.put("DIRT", 1);
		tmp2.put("world2", tmp);
				
		// Setup configuration
		loadConfig(tmp2);
		
		// Register events
		getServer().getPluginManager().registerEvents(this, this);
		// Pull blockPay from config
		try {
			blockPay = new HashMap(this.getConfig().getConfigurationSection("blocks").getValues(false));
			worldPays = new HashMap(this.getConfig().getConfigurationSection("worlds").getValues(true));
		} catch (Exception e) {
			getLogger().info("Failed at loading block pays. Are they integers?");
			getLogger().info(e.toString());
		}
		saveDelay = this.getConfig().getInt("saveDelay");
		vipFactor = this.getConfig().getInt("vipFactor");
		payType = this.getConfig().getInt("payoutType");
		debugging = this.getConfig().getInt("debug");
			
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
		
		if (saveDelay>0) {
			taskId = this.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
			public void run() {
				saveAll();
			}}, saveDelay * 20, saveDelay * 20);
		}
	}
	
	// Called when the plugin is disabled
	@Override
	public void onDisable() {
		// Save every players data
		saveAll();
		
		// Stop the scheduler from trying to save
		this.getServer().getScheduler().cancelTask(taskId);
	}
	
	// Called when a player/console uses the blockcount command
	public boolean onCommand(CommandSender sender, Command command, String cmdLabel, String[] args) {
		if (command.getName().equalsIgnoreCase("blockcount")) {
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
					messagePlayer(p, "  Paid BTC: " + ChatColor.RESET + paid.get(p.getName()).toPlainString());
				}
			// If the player/console is searching for another player
			} else {
				String pName = args[0];
				
				if (pName.trim().equalsIgnoreCase("toggle")) {
					if (sender instanceof Player) {
						showScr.put(sender.getName(), !showScr.get(sender.getName()));
						if (!showScr.get(sender.getName())) {
							((Player) sender).setScoreboard(scrManager.getNewScoreboard());
						} else {
							Objective tmpObj = scrBoards.get(sender.getName()).getObjective("blockcounter");
							tmpObj.getScore(blocksPly).setScore(blocks.get(sender.getName()));
							tmpObj.getScore(satPly).setScore(btc.get(sender.getName()).setScale(8).divide(new BigDecimal("0.00000001")).intValue());
							((Player) sender).setScoreboard(scrBoards.get(sender.getName()));
						}
						// Save to database
						String q = "INSERT INTO "+this.getConfig().getString("SQL.table")
								+ " (name, showScr) VALUES ('"+sender.getName() + "', "
								+ (showScr.get(sender.getName())?1:0)+") ON DUPLICATE KEY "
								+ "UPDATE showScr = " + (showScr.get(sender.getName())?1:0)+";";
						try {
							st.executeUpdate(q);
						} catch (SQLException e) {
							getLogger().severe("Failed to save a players showScr.");
							getLogger().severe("Player: " + sender.getName());
							getLogger().severe(q);
						}
					}
					return true;
				}
	
				// If we don't have values stored for the player
				String s1, s2, s3, s4;
				s1 = "Player stats for " + pName + ":";
				if (blocks.get(pName) == null) {
					s2 = "  Blocks: " + ChatColor.RESET + Integer.toString(getValue(pName, "total"));
					s3 = "  BTC: " + ChatColor.RESET + getDecimal(pName, "btc").toPlainString();
					s4 = "  Paid blocks: " + ChatColor.RESET + getDecimal(pName, "paid").toPlainString();
				// If we already have them saved
				} else {
					s2 = "  Blocks: " + ChatColor.RESET + Integer.toString(blocks.get(pName));
					s3 = "  BTC: " + ChatColor.RESET + btc.get(pName).toPlainString();
					s4 = "  Paid BTC: " + ChatColor.RESET + paid.get(pName).toPlainString();
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
		} else {
			if (sender instanceof Player) {
				showScr.put(sender.getName(), !showScr.get(sender.getName()));
				if (!showScr.get(sender.getName())) {
					((Player) sender).setScoreboard(scrManager.getNewScoreboard());
				} else {
					Objective tmpObj = scrBoards.get(sender.getName()).getObjective("blockcounter");
					tmpObj.getScore(blocksPly).setScore(blocks.get(sender.getName()));
					tmpObj.getScore(satPly).setScore(btc.get(sender.getName()).setScale(8).divide(new BigDecimal("0.00000001")).intValue());
					((Player) sender).setScoreboard(scrBoards.get(sender.getName()));
				}
				// Save to database
				String q = "INSERT INTO "+this.getConfig().getString("SQL.table")
						+ " (name, showScr) VALUES ('"+sender.getName() + "', "
						+ (showScr.get(sender.getName())?1:0)+") ON DUPLICATE KEY "
						+ "UPDATE showScr = " + (showScr.get(sender.getName())?1:0)+";";
				try {
					st.executeUpdate(q);
				} catch (SQLException e) {
					getLogger().severe("Failed to save a players showScr.");
					getLogger().severe("Player: " + sender.getName());
					getLogger().severe(q);
				}
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
			addBlock(p, b, 1);
		} else {
			// If this block isn't in the history of this player
			if (!arrContains( history.get(p.getName()) , new int[]{b.getX(),b.getY(),b.getZ()} ) == true) {
				addBlock(p, b, 1);
			}
			updateHistory(p, b.getX(), b.getY(), b.getZ());
		}
	}
	
	// Someone placed a block
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onPlace(BlockPlaceEvent e) {
		Player p = e.getPlayer();
		Block b = e.getBlockPlaced();
		
		if (history.get(p.getName())==null) {
			// If this is the first block broken
			setupHistory(p, b.getX(), b.getY(), b.getZ());
			addBlock(p, b, 0);
		} else {
			// If this block isn't in the history of this player
			if (!arrContains( history.get(p.getName()) , new int[]{b.getX(),b.getY(),b.getZ()} ) == true) {
				addBlock(p, b, 0);
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
		// Debug
		if (debugging==1) {
			getLogger().info("Saving all players.");
		}
		
		// Save every players data
		for (Player p : this.getServer().getOnlinePlayers()) {
			savePlayer(p);
		}
	}
	
	// Load the config (or create it)
	private void loadConfig(HashMap<String, HashMap<String, Integer>> worldsMap) {
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
				+ "  If set to 0, saves the relevant player on block break.\r\n"
				+ "\r\n"
				+ "vipFactor:\r\n"
				+ "  The amount to duplicate payout with for people with blockcounter.vippay permission\r\n"
				+ "\r\n"
				+ "payoutType:\r\n"
				+ "  An integer that defines the way payouts is calculated.\r\n"
				+ "  0 - Flat payout. Uses the value of AIR for all blocks.\r\n"
				+ "  1 - Per-block payout. Uses each blocks own payout.\r\n"
				+ "  3 - Breaking is flat, placing is per-block.\r\n"
				+ "  4 - Breaking is per-block, placing is flat.\r\n"
				+ "\r\n"
				+ "debug:\r\n"
				+ "  An integer that defines whether debugging mode is enabled.\r\n"
				+ "  0 - Disabled.\r\n"
				+ "  1 - Enabled.\r\n"
				+ "blocks:\r\n"
				+ "  A list of all available blocks and their payouts. Counted in satoshi.\r\n"
				+ "\r\n"
				+ "worlds:\r\n"
				+ "  A list of worlds with special payouts. Can have their own payoutType \r\n"
				+ "  or block list set.");
		
		this.getConfig().addDefault("SQL.host", "localhost");
		this.getConfig().addDefault("SQL.port", "3306");
		this.getConfig().addDefault("SQL.database", "blockcounter");
		this.getConfig().addDefault("SQL.user", "bukkit");
		this.getConfig().addDefault("SQL.pass", "bukkitpass");
		this.getConfig().addDefault("SQL.table", "players");
		
		this.getConfig().addDefault("saveDelay", 30);
		this.getConfig().addDefault("vipFactor", 2);
		this.getConfig().addDefault("payoutType", 0);
		this.getConfig().addDefault("debug", 0);
		
		this.getConfig().addDefault("blocks", blockPay);
		
		this.getConfig().addDefault("worlds", worldsMap);
		
		this.getConfig().options().copyDefaults(true);
		this.saveConfig();
	}
	
	// Add payout for a new block
	private void addBlock(Player p, Block b, int type) {
		// Quick and hacky solution to tilling ALL the blocks
		if (type==0&&b.getType().name()=="SOIL") {
			return;
		}
		// type is placing/breaking. Placing = 0, breaking = 1
		// Debug message
		String msg = "You've "+((type==0)?"placed":"broken")+" a "
				+ChatColor.RESET+b.getType().name()+ChatColor.YELLOW
				+" block in world "+ChatColor.RESET+b.getWorld().getName()
				+ChatColor.YELLOW+" worth "+ChatColor.RESET;
		
		// Get paytype for world
		int lclPayType = 0;
		try {
			lclPayType = Integer.valueOf(worldPays.get(b.getWorld().getName()+".payoutType"));
		} catch(Exception e) {
			lclPayType = payType;
		}
		
		// Get worth of block
		int lclBlockPay = 0;
		// If payout is flat
		if (lclPayType == 0 || (type==1 && lclPayType==3) || (type==0 && lclPayType==4)) {
			try {
				lclBlockPay = Integer.valueOf(worldPays.get(b.getWorld().getName()+".AIR"));
			} catch(Exception e) {
				lclBlockPay = blockPay.get("AIR");
			}
		// Otherwise
		} else {
			try {
				lclBlockPay = Integer.valueOf(worldPays.get(b.getWorld().getName()+"."+b.getType().name()));
			} catch (Exception e) {
				try {
					lclBlockPay = blockPay.get(b.getType().name());
				} catch (Exception ex) {
					getLogger().severe("Failed to retrieve a blocks worth.");
					getLogger().severe("Block class:" + ((b!=null)?b.toString():"null"));
					getLogger().severe("Block type: " + ((b.getType()!=null)?b.getType().name():"null"));
					getLogger().severe("Player: " + p.getName());
					getLogger().severe(ex.toString());
				}
			}
		}
		
		// For calculating BTC
		BigDecimal deltBtc = new BigDecimal("0.00000001");
		
		// Calculate BTC worth of block
		deltBtc = deltBtc.multiply(new BigDecimal(lclBlockPay));
		
		// Stop if the block isn't worth anything
		if (deltBtc.compareTo(new BigDecimal("0"))<=0) {
			if (debugging==1) {
				getLogger().info("Worthless block mined. Player: " + p.getName() + ", VIP: " + p.hasPermission("blockcounter.vippay"));
				messagePlayer(p,"That block isn't worth anything.");
			}
			return;
		}
		
		// Debug message
		msg+=deltBtc.toPlainString()+ChatColor.YELLOW+" BTC.";
		
		// Multiply if player high enough rank
		if (p.hasPermission("blockcounter.vippay")) {
			if (debugging==1) {
				// Debug message
				msg+=ChatColor.GREEN+" You have VIP.";
			}
			deltBtc = deltBtc.multiply(new BigDecimal(vipFactor));
		}
		
		// If debugging, tell player and server
		if (debugging==1) {
			getLogger().info("Crediting block. Player: " + p.getName() + ", VIP: " + p.hasPermission("blockcounter.vippay") + ", worth: " + deltBtc.toPlainString());
			messagePlayer(p,msg);
		}
		
		// Save
		blocks.put(p.getName(), blocks.get(p.getName()) + 1);
		btc.put(p.getName(), btc.get(p.getName()).add(deltBtc));
		
		// Update GUI
		if (showScr.get(p.getName())) {
			Objective tmpObj = scrBoards.get(p.getName()).getObjective("blockcounter");
			tmpObj.getScore(blocksPly).setScore(blocks.get(p.getName()));
			tmpObj.getScore(satPly).setScore(btc.get(p.getName()).setScale(8).divide(new BigDecimal("0.00000001")).intValue());
			p.setScoreboard(scrBoards.get(p.getName()));
		}
		
		// Save to database if relevant
		if (saveDelay == 0) {
			savePlayer(p);
		}
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
		BigDecimal tmpBtc = getDecimal(player.getName(), "btc");
		BigDecimal tmpPaid = getDecimal(player.getName(), "paid");
		boolean tmpShowScr = getValue(player.getName(), "showScr")==1 ? true : false;
		
		// Save in hashmaps
		blocks.put(player.getName(), tmpBlocks);
		oldBlocks.put(player.getName(), tmpBlocks);
		btc.put(player.getName(), tmpBtc);
		oldBtc.put(player.getName(), tmpBtc);
		paid.put(player.getName(), tmpPaid);
		
		// Setup GUI stuff
		Scoreboard tmpBoard = scrManager.getNewScoreboard();
		Objective tmpObj = tmpBoard.registerNewObjective("blockcounter", "dummy");
		tmpObj.setDisplaySlot(DisplaySlot.SIDEBAR);
		tmpObj.setDisplayName("Blockcounter");
		Score tmpScore = tmpObj.getScore(blocksPly);
		tmpScore.setScore(tmpBlocks);
		tmpScore = tmpObj.getScore(satPly);
		tmpScore.setScore(tmpBtc.setScale(8).divide(new BigDecimal("0.00000001")).intValue());
		scrBoards.put(player.getName(), tmpBoard);
		showScr.put(player.getName(), tmpShowScr);
		if (tmpShowScr) {
			if (player.isOnline()) {
				player.setScoreboard(tmpBoard);
			}
		}
	}
	
	// Save to database for a given player
	private void savePlayer(Player player) {
		// Get values from hashmaps
		int tmpBlocks = blocks.get(player.getName());
		BigDecimal tmpBtc = btc.get(player.getName());
		
		int oBlocks = oldBlocks.get(player.getName());
		BigDecimal oBtc = oldBtc.get(player.getName());
		
		// Insert into database
		// Create query
					String q = "INSERT INTO "+this.getConfig().getString("SQL.table")
							+ " (name, total, btc) VALUES ('"+player.getName()+"', "
							+ Integer.toString(tmpBlocks)+", "+tmpBtc.toString()
							+ ") ON DUPLICATE KEY "
							+ "UPDATE total = if (total = "+Integer.toString(oBlocks)
							+ ", VALUES(total), total + "+(tmpBlocks-oBlocks)+"), "
							+ "btc = if (btc = "+oBtc.toPlainString()+", VALUES(btc)"
							+ ", btc + "+tmpBtc.subtract(oBtc).toPlainString()+");";
		try {					
			st.executeUpdate(q);
		} catch (SQLException e) {
			getLogger().severe("Failed to save a players stats!");
			getLogger().severe("Player: " + player.getName());
			getLogger().severe(e.toString());
			getLogger().severe(q);
			
			// Tell the player
			if(debugging==1) {
				messagePlayer(player,ChatColor.RED+"We failed to save your stats!");
			}
		}
		
		setupPlayer(player);
	}
	
	private int getValue(String name, String value) {
		try {
			// Retrieve the total block count from database
			ResultSet res = st.executeQuery("SELECT "+value+" FROM "+this.getConfig().getString("SQL.table")+" WHERE name='"+name+"';");
			res.next();
			// return the value
			return res.getInt(value);
		} catch (SQLException e) {
			// Log the failure
			getLogger().warning("Failed to load a players "+value+" blocks.");
			getLogger().warning("Player: " + name);
			getLogger().warning(e.toString());
			
			// Tell the player
			if(debugging==1) {
				Player ply = this.getServer().getPlayer(name);
				if (ply != null && ply.isOnline()) {
					messagePlayer(this.getServer().getPlayer(name),ChatColor.RED+"We failed to load your "+value+" block count!");
				}
			}
		}
		if (value=="showScr") return 1;
		return 0;
	}
	
	//Due to needed precision, this needs to return a BigDecimal
	private BigDecimal getDecimal(String name, String value) {
		String  q = "SELECT "+value+" FROM "+this.getConfig().getString("SQL.table")+" WHERE name='"+name+"';";
		try {
			// Retrieve the total block count from database
			ResultSet res = st.executeQuery(q);
			res.next();
			// return the value
			return res.getBigDecimal(value).setScale(8, RoundingMode.HALF_UP);
		} catch (SQLException e) {
			// Log the failure, and tell the player
			getLogger().warning("Failed to load a players "+value+" balance.");
			getLogger().warning("Player: " + name);
			getLogger().warning(e.toString());
			getLogger().warning(q);
			
			// Tell the player
			if(debugging==1) {
				Player ply = this.getServer().getPlayer(name);
				if (ply != null && ply.isOnline()) {
					messagePlayer(this.getServer().getPlayer(name),ChatColor.RED+"We failed to load your "+value+" balance!");
				}
			}
		}
		return new BigDecimal("0.00000000");
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
