package heufybot.core;

import heufybot.core.IRC.ConnectionState;
import heufybot.core.cap.CAPException;
import heufybot.core.cap.CapHandler;
import heufybot.core.events.types.*;
import heufybot.utils.StringUtils;
import heufybot.utils.WhoisBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

public class InputParser 
{
	private IRC irc;
	private int nickSuffix;
	private boolean capEndSent;
	private List<CapHandler> finishedHandlers;
	private WhoisBuilder whoisBuilder;
	
	public InputParser(IRC irc)
	{
		this.irc = irc;
		this.nickSuffix = 1;
		this.finishedHandlers = new ArrayList<CapHandler>();
		this.whoisBuilder = new WhoisBuilder();
	}
	
	public void parseLine(String line)
	{
		List<String> parsedLine = this.tokenizeLine(line);
		
		String senderInfo = "";
		if (parsedLine.get(0).charAt(0) == ':')
		{
			senderInfo = parsedLine.remove(0);
		}
		
		String command = parsedLine.remove(0).toUpperCase();
		
		if (command.equals("PING"))
		{
			irc.cmdPONG(parsedLine.get(0));
			irc.getEventListenerManager().dispatchEvent(new PingEvent(parsedLine.get(0)));
			return;
		}
		else if(command.startsWith("ERROR"))
		{
			//Connection closed by server
			String errorMessage = parsedLine.get(0).toLowerCase();
			irc.getEventListenerManager().dispatchEvent(new ErrorEvent(parsedLine.get(0)));
			
			//I really need to clean up this piece of code some time :|
			if(errorMessage.contains("[killed:") || errorMessage.contains("lined") || errorMessage.contains("[quit:"))
			{
				irc.disconnect(false);
			}
			else
			{
				irc.disconnect(true);
			}
			return;
		}
		
		String sourceNick;
		String sourceLogin = "";
		String sourceHostname = "";
		String target = !parsedLine.isEmpty() ? parsedLine.get(0) : "";
		
		if(target.startsWith(":"))
		{
			target = target.substring(1);
		}
		
		int exclamationPosition = senderInfo.indexOf("!");
		int atPosition = senderInfo.indexOf("@");
		
		if (senderInfo.startsWith(":"))
		{
			if(exclamationPosition > 0 && atPosition > 0 && exclamationPosition < atPosition)
			{
				//This is a command
				sourceNick = senderInfo.substring(1, exclamationPosition);
				sourceLogin = senderInfo.substring(exclamationPosition + 1, atPosition);
				sourceHostname = senderInfo.substring(atPosition + 1);
			}
			else
			{
				//This is probably a server response
				int code = StringUtils.tryParseInt(command);
				if(code != -1)
				{
					if(irc.getConnectionState() != ConnectionState.Connected)
					{
						handleConnect(line, parsedLine, command);
					}
					else
					{
						handleServerResponse(line, parsedLine, command);
					}
					return;
				}
				else
				{
					//Could be a nick without a login or host
					sourceNick = senderInfo;
				}
			}
		}
		else
		{
			//No idea what this is, pass it to the CAP handlers
			if(irc.getConnectionState() != ConnectionState.Connected)
			{
				for(CapHandler currentHandler : irc.getConfig().getCapHandlers())
				{
					if(currentHandler.handleUnknown(irc, line))
					{
						finishedHandlers.add(currentHandler);
					}
				}
			}
			return;
		}
		
		if(sourceNick.startsWith(":"))
		{
			sourceNick = sourceNick.substring(1);
		}
		
		if(irc.getConnectionState() != ConnectionState.Connected && command.equals("CAP"))
		{
			handleConnect(line, parsedLine, command);
		}
		else
		{
			handleCommand(line, parsedLine, sourceNick, sourceLogin, sourceHostname, command, target);
		}
	}
	
	private void handleConnect(String line, List<String> parsedLine, String code)
	{
		if(code.equals("001"))
		{
			//001 RPL_WELCOME
			irc.setConnectionState(ConnectionState.Connected);
			irc.setLoggedInNick(irc.getConfig().getNickname() + (nickSuffix == 1 ? "" : nickSuffix));
			
			nickSuffix = 1;
			
			Logger.log("*** Logged onto server");
			
			//Reset CAP stuff
			capEndSent = false;
			finishedHandlers.clear();
			
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
			
			if(irc.getConfig().getPasswordType() == Config.PasswordType.NickServPass)
			{
				irc.nickservIdentify(irc.getConfig().getPassword());
			}
			
			if(irc.getConfig().getAutoJoinEnabled() && irc.getConfig().getAutoJoinChannels().size() > 0)
			{
				for(String channelName : irc.getConfig().getAutoJoinChannels())
				{
					String[] channel = channelName.split(" ");
					if(channel.length > 1)
					{
						//This channel has a key
						irc.cmdJOIN(channel[0], channel[1]);
					}
					else
					{
						//This channel doesn't need a key
						irc.cmdJOIN(channel[0], "");
					}
				}
			}
		}
		else if(code.equals("433"))
		{
			//443 ERR_NICKNAMEINUSE
			if(irc.getConfig().getAutoNickChange())
			{
				//Try a different nickname
				String usedNick = parsedLine.get(1);
				nickSuffix++;
				Logger.log("*** Nickname " + usedNick + " was already taken. Trying " + irc.getConfig().getNickname() + nickSuffix + "...");
				irc.cmdNICK(irc.getConfig().getNickname() + nickSuffix);
			}
			else
			{
				//Give up
				Logger.error("IRC Login", "Login failed. Nickname was already taken");
				irc.disconnect(false);
			}
		}
		else if(code.equals("451"))
		{
			//451 ERR_NOTREGISTERED
			//The server does not support CAP. No action required
		}
		else if(code.startsWith("4") || code.startsWith("5") && !code.equals("439"))
		{
			//439 ERR_TARGETTOOFAST : No action required
			//Couldn't login. Disconnect.
			Logger.error("IRC Login", "Login failed.");
			irc.disconnect(false);
		}
		else if(code.equals("CAP"))
		{
			String capCommand = parsedLine.get(1);
			List<String> capParams = Arrays.asList(parsedLine.get(2).split(" "));
			if(capCommand.equals("LS"))
			{
				Logger.log("*** Supported capabilities: " + StringUtils.join(capParams, ", "));
				for(CapHandler currentCapHandler : irc.getConfig().getCapHandlers())
				{
					try
					{
						if(currentCapHandler.handleLS(irc, capParams))
						{
							finishedHandlers.add(currentCapHandler);
						}
					}
					catch(CAPException e)
					{
						finishedHandlers.add(currentCapHandler);
						Logger.error("CAP Enabling", e.getMessage());
					}
				}
			}
			else if(capCommand.equals("ACK"))
			{
				for(CapHandler currentCapHandler : irc.getConfig().getCapHandlers())
				{
					if(currentCapHandler.handleACK(irc, capParams))
					{
						finishedHandlers.add(currentCapHandler);
					}
				}
			}
			else if(capCommand.equals("NAK"))
			{
				for(CapHandler currentCapHandler : irc.getConfig().getCapHandlers())
				{
					try
					{
						if(currentCapHandler.handleNAK(irc, capParams))
						{
							finishedHandlers.add(currentCapHandler);
						}
					}
					catch(CAPException e)
					{
						Logger.error("CAP Enabling", e.getMessage());
					}
				}
			}
			else
			{
				for(CapHandler currentHandler : irc.getConfig().getCapHandlers())
				{
					if(currentHandler.handleUnknown(irc, line))
					{
						finishedHandlers.add(currentHandler);
					}
				}
			}
		}
		else
		{
			for(CapHandler currentHandler : irc.getConfig().getCapHandlers())
			{
				if(currentHandler.handleUnknown(irc, line))
				{
					finishedHandlers.add(currentHandler);
				}
			}
		}
		
		if(!capEndSent && finishedHandlers.containsAll(irc.getConfig().getCapHandlers()))
		{
			capEndSent = true;
			irc.cmdCAP("END", "");
		}
	}
	
	private void handleServerResponse(String rawResponse, List<String> parsedLine, String code)
	{
		if(code.equals("002") || code.equals("003"))
		{
			//002 RPL_YOURHOST
			//003 RPL_CREATED
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
		}
		else if(code.equals("004"))
		{
			//004 RPL_MYINFO
			irc.getServerInfo().setServer(parsedLine.get(1));
			irc.getServerInfo().setServerVersion(parsedLine.get(2));
			
			irc.getServerInfo().getUserModes().clear();
			for(int i = 0; i < parsedLine.get(3).length(); i++)
			{
				irc.getServerInfo().getUserModes().add(Character.toString(parsedLine.get(3).charAt(i)));
			}
			
			if(irc.getServerInfo().getUserModes().contains("B"))
			{
				irc.cmdMODE(irc.getNickname(), "+B");
			}
			
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(rawResponse.split(irc.getNickname() + " ")[1]));
		}
		else if (code.equals("005"))
		{
			//005 RPL_ISUPPORT			
			//Server information. Might do something with this later.
			if(rawResponse.contains("PREFIX="))
			{
				String prefixes = rawResponse.split("PREFIX=")[1];
				prefixes = prefixes.substring(0, prefixes.indexOf(" "));
				irc.getServerInfo().setUserPrefixes(this.getUserPrefixes(prefixes));
				irc.getServerInfo().setReverseUserPrefixes(this.getReverseUserPrefixes(prefixes));
			}
			if(rawResponse.contains("CHANTYPES="))
			{
				String chantypes = rawResponse.split("CHANTYPES=")[1];
				chantypes = chantypes.substring(0, chantypes.indexOf(" "));
				irc.getServerInfo().setChantypes(chantypes);
			}
			if(rawResponse.contains("CHANMODES="))
			{
				String rawChanmodes = rawResponse.split("CHANMODES=")[1];
				rawChanmodes = rawChanmodes.substring(0, rawChanmodes.indexOf(" "));
				String[] chanmodes = rawChanmodes.split(",");
				
				irc.getServerInfo().getChannelListModes().clear();
				irc.getServerInfo().getChannelNoArgsModes().clear();
				irc.getServerInfo().getChannelSetArgsModes().clear();
				irc.getServerInfo().getChannelSetUnsetArgsModes().clear();
				
				for(int i = 0; i < chanmodes[0].length(); i++)
				{
					irc.getServerInfo().getChannelListModes().add(Character.toString(chanmodes[0].charAt(i)));
				}
				
				for(int i = 0; i < chanmodes[1].length(); i++)
				{
					irc.getServerInfo().getChannelSetUnsetArgsModes().add(Character.toString(chanmodes[1].charAt(i)));
				}
				
				for(int i = 0; i < chanmodes[2].length(); i++)
				{
					irc.getServerInfo().getChannelSetArgsModes().add(Character.toString(chanmodes[2].charAt(i)));
				}
				
				for(int i = 0; i < chanmodes[3].length(); i++)
				{
					irc.getServerInfo().getChannelNoArgsModes().add(Character.toString(chanmodes[3].charAt(i)));
				}
			}
			if(rawResponse.contains("NETWORK="))
			{
				String network = rawResponse.split("NETWORK=")[1];
				network = network.substring(0, network.indexOf(" "));
				irc.getServerInfo().setNetwork(network);
			}
			
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(rawResponse.split(irc.getNickname() + " ")[1]));
		}
		else if(code.equals("042"))
		{
			//042 RPL_YOURID
			//UUID, might do something with it later. Just log it for now.
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1) + " " + parsedLine.get(2)));
		}
		else if(code.equals("251") || code.equals("255") || code.equals("265") || code.equals("266"))
		{
			//251 RPL_LUSERCLIENT
			//255 RPL_LUSERME
			//265 RPL_LOCALUSERS
			//266 RPL_GLOBALUSERS
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
		}
		else if(code.equals("252") || code.equals("254") || code.equals("396"))
		{
			//252 RPL_LUSEROP
			//254 RPL_LUSERCHANNELS 
			//396 RPL_HOSTHIDDEN
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1) + " " + parsedLine.get(2)));
		}
		else if(code.equals("311"))
		{
			//311 RPL_WHOISUSER
			whoisBuilder.setNickname(parsedLine.get(1));
			whoisBuilder.setLogin(parsedLine.get(2));
			whoisBuilder.setHostname(parsedLine.get(3));
			whoisBuilder.setRealname(parsedLine.get(5));	
		}
		else if(code.equals("312"))
		{
			//312 RPL_WHOISSERVER
			whoisBuilder.setServer(parsedLine.get(2));
			whoisBuilder.setServerInfo(parsedLine.get(3));
		}
		else if(code.equals("313"))
		{
			//313 RPL_WHOISOPERATOR
			whoisBuilder.setOperPrivs(parsedLine.get(2));
		}
		else if(code.equals("315"))
		{
			//315 RPL_ENDOFWHO
			//No action required
		}
		else if(code.equals("317"))
		{
			//317 RPL_WHOISIDLE
			whoisBuilder.setIdleSeconds(StringUtils.tryParseLong(parsedLine.get(2)));
			
			//Sign-on time is not in the RFC, but most deamons support this nowadays anyway
			whoisBuilder.setSignOnTime(StringUtils.tryParseLong(parsedLine.get(3)));
		}
		else if(code.equals("318"))
		{
			//318 RPL_ENDOFWHOIS
			irc.getEventListenerManager().dispatchEvent(new WhoisEvent(whoisBuilder));
			whoisBuilder = new WhoisBuilder();
		}
		else if(code.equals("319"))
		{
			//319 RPL_WHOISCHANNELS
			whoisBuilder.setChannels(StringUtils.parseStringtoList(parsedLine.get(2), " "));
		}
		else if(code.equals("324"))
		{
			//324 RPL_CHANNELMODEIS 
			Channel channel = irc.getChannel(parsedLine.get(1));
			handleMode("", channel.getName(), parsedLine.get(2));
			irc.getEventListenerManager().dispatchEvent(new ServerResponseChannelEvent(channel, "Channel modes currently set: " + parsedLine.get(2)));
		}
		else if(code.equals("329"))
		{
			//329 RPL_CREATIONTIME
			Channel channel = irc.getChannel(parsedLine.get(1));
			irc.getEventListenerManager().dispatchEvent(new ServerResponseChannelEvent(channel, "Channel was created on " + new Date(StringUtils.tryParseLong(parsedLine.get(2)) * 1000)));
		}
		else if(code.equals("332"))
		{
			//332 RPL_TOPIC
			Channel channel = irc.getChannel(parsedLine.get(1));
			channel.setTopic(parsedLine.get(2));
			irc.getEventListenerManager().dispatchEvent(new ServerResponseChannelEvent(channel, "Topic is \'" + parsedLine.get(2) + "\'"));
		}
		else if(code.equals("333"))
		{
			//333 RPL_TOPICWHOTIME
			Channel channel = irc.getChannel(parsedLine.get(1));
			channel.setTopicSetter(parsedLine.get(2));
			
			long topicTimestamp = StringUtils.tryParseLong(parsedLine.get(3));
			
			channel.setTopicSetTimestamp(topicTimestamp);
			irc.getEventListenerManager().dispatchEvent(new ServerResponseChannelEvent(channel, "Set by " + parsedLine.get(2) + " on " + new Date(topicTimestamp * 1000)));
		}
		else if(code.equals("352"))
		{
			//352 RPL_WHOREPLY			
			Channel channel = irc.getChannel(parsedLine.get(1));
			User user = irc.getUser(parsedLine.get(5));

			user.setLogin(parsedLine.get(2));
			user.setHostname(parsedLine.get(3));
			user.setServer(parsedLine.get(4));
			
			if(channel.getUser(user.getNickname()) == null)
			{
				channel.addUser(user);
			}
			
			List<String> flags = StringUtils.parseStringtoList(parsedLine.get(6), "");
			flags.remove(0);
			
			if(flags.remove(0).equals("G"))
			{
				user.setAway(true);
			}
			if(flags.size() > 0 && flags.remove(0).equals("*"))
			{
				user.setOper(true);
			}
			
			String modes = StringUtils.join(flags, "");
			
			for(int i = 0; i < modes.length(); i++)
			{
				String mode = irc.getServerInfo().getReverseUserPrefixes().get(Character.toString(modes.charAt(i)));
				channel.parseModeChangeOnUser(user, "+" + mode);
			}
			
			String rawEnd = parsedLine.get(7);
			user.setHops(StringUtils.tryParseInt(rawEnd.substring(0, rawEnd.indexOf(" "))));
			user.setRealname(rawEnd.substring(rawEnd.indexOf(" ") + 1));
		}
		else if(code.equals("353"))
		{
			//353 RPL_NAMREPLY
			Channel channel = irc.getChannel(parsedLine.get(2));
			String[] users = parsedLine.get(3).split(" ");
			
			for(int i = 0; i < users.length; i++)
			{
				String prefixes = "";
				String nickname = users[i];
				LinkedHashMap<String, String> reverseUserPrefixes = irc.getServerInfo().getReverseUserPrefixes();
				for(int j = 0; j < reverseUserPrefixes.size(); j++)
				{
					String firstCharString = "" + nickname.charAt(0);
					if(reverseUserPrefixes.containsKey(firstCharString))
					{
						prefixes += reverseUserPrefixes.get(firstCharString);
						nickname = nickname.substring(1);
					}
				}
				
				User user = irc.getUser(nickname);
				if(channel.getUser(user.getNickname()) == null)
				{
					channel.addUser(user);
				}
				channel.parseModeChangeOnUser(user, "+" + prefixes);
			}
		}
		else if(code.equals("366"))
		{
			//366 RPL_ENDOFNAMES
			//No action required
		}
		else if(code.equals("372"))
		{
			//372 RPL_MOTD
			irc.getServerInfo().appendMotd(parsedLine.get(1) + "\n");
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
		}
		else if(code.equals("375"))
		{
			//375 RPL_MOTDSTART 
			irc.getServerInfo().setMotd("");
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
		}
		else if(code.equals("376"))
		{
			//376 RPL_ENDOFMOTD
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent(parsedLine.get(1)));
		}
		else
		{
			//Not parsed (yet)
			irc.getEventListenerManager().dispatchEvent(new ServerResponseEvent("(" + code + ") " + rawResponse.substring(rawResponse.indexOf(irc.getNickname() + " ") + irc.getNickname().length() + 1)));
		}
	}
	
	private void handleCommand(String line, List<String> parsedLine, String sourceNick, String sourceLogin, String sourceHostname, String command, String target)
	{
		User source = irc.getUser(sourceNick);
		Channel channel = irc.getChannel(target);
		String message = parsedLine.size() >= 2 ? parsedLine.get(1) : "";
		
		if (command.equals("PRIVMSG") && message.startsWith("\u0001") && message.endsWith("\u0001"))
		{
			//Message is a CTCP request
			String request = message.substring(1, message.length() - 1);
			if (request.toUpperCase().startsWith("ACTION ") && channel != null) 
			{
				// ACTION request
				irc.getEventListenerManager().dispatchEvent(new ActionEvent(source, channel, request.substring(7)));
			}
			else if(request.toUpperCase().startsWith("ACTION "))
			{
				// ACTION request in a PM
				irc.getEventListenerManager().dispatchEvent(new PMActionEvent(source, request.substring(7)));
			}
			else if(request.toUpperCase().startsWith("PING "))
			{
				irc.ctcpReply(sourceNick, "PING", request.substring(5));
				irc.getEventListenerManager().dispatchEvent(new CTCPRequestEvent(source, "PING"));
			}
			else if(request.toUpperCase().equals("VERSION"))
			{
				irc.ctcpReply(sourceNick, "VERSION", "RE_HeufyBot V" + HeufyBot.VERSION + ", OS: " + System.getProperty("os.name") + " ("+ System.getProperty("os.version") + ")," + System.getProperty("os.arch"));
				irc.getEventListenerManager().dispatchEvent(new CTCPRequestEvent(source, "VERSION"));
			}
			else if(request.toUpperCase().equals("TIME"))
			{
				irc.ctcpReply(sourceNick, "TIME", new Date().toString());
				irc.getEventListenerManager().dispatchEvent(new CTCPRequestEvent(source, "TIME"));
			}
			else if(request.toUpperCase().equals("FINGER"))
			{
				irc.ctcpReply(sourceNick, "FINGER", "Why would you finger a bot?!");
				irc.getEventListenerManager().dispatchEvent(new CTCPRequestEvent(source, "FINGER"));
			}
		}
		else if(command.equals("PRIVMSG") && channel != null)
		{
			//Message to the channel
			irc.getEventListenerManager().dispatchEvent(new MessageEvent(source, channel, message));
		}
		else if(command.equals("PRIVMSG"))
		{
			//Private message
			if(source.getHostname().equals(""))
			{
				//We don't this user yet, so send a WHOIS
				irc.cmdWHOIS(sourceNick);
			}
			irc.getEventListenerManager().dispatchEvent(new PMMessageEvent(source, message));
		}
		else if(command.equals("JOIN"))
		{
			//Someone joins the channel
			if(sourceNick.equalsIgnoreCase(irc.getNickname()))
			{
				//The bot is joining the channel, do setup
				channel = new Channel(target);
				irc.getChannels().add(channel);
				
				irc.cmdWHO(channel.getName());
				irc.cmdMODE(channel.getName(), "");
				
				source = new User(sourceNick, sourceLogin, sourceHostname);
			}
			else
			{
				//Someone else is joining the channel
				source.setLogin(sourceLogin);
				source.setHostname(sourceHostname);

				channel.addUser(source);
			}
			irc.getEventListenerManager().dispatchEvent(new JoinEvent(source, channel));
		}
		else if(command.equals("PART"))
		{
			if(sourceNick.equalsIgnoreCase(irc.getNickname()))
			{
				//The bot is leaving the channel		
				irc.getChannels().remove(channel);
			}
			else
			{
				//Someone else is leaving the channel
				irc.getChannel(target).removeUser(source);
			}
			
			boolean noCommonChannels = true;
			for(Channel channel2 : irc.getChannels())
			{
				if(channel2.getUser(sourceNick) != null)
				{
					noCommonChannels = false;
				}
			}
			
			if(noCommonChannels)
			{
				irc.getUsers().remove(source);
			}
			
			irc.getEventListenerManager().dispatchEvent(new PartEvent(source, channel, message));
		}
		else if(command.equals("NICK"))
		{
			//Someone is changing their nick
			String newNick = target;
			
			for(Channel channel2 : irc.getChannels())
			{
				User user = channel2.getUser(sourceNick);
				if(user != null)
				{
					user.setNickname(newNick);
				}
			}
			if(sourceNick.equalsIgnoreCase(irc.getNickname()))
			{
				//The bot's nick is changed
				irc.setLoggedInNick(newNick);
			}
			
			irc.getEventListenerManager().dispatchEvent(new NickChangeEvent(source, newNick, sourceNick));
		}
		else if(command.equals("NOTICE"))
		{
			//Someone sent a notice
			if (channel == null) 
			{
				irc.getEventListenerManager().dispatchEvent(new NoticeEvent(sourceNick, message));
			}
			else
			{
				irc.getEventListenerManager().dispatchEvent(new ChannelNoticeEvent(sourceNick, channel, message));
			}
		}
		else if(command.equals("QUIT"))
		{
			//Someone quit the server
			if(!sourceNick.equalsIgnoreCase(irc.getNickname()))
			{
				irc.getEventListenerManager().dispatchEvent(new QuitEvent(source, target));
				for(Channel channel2 : irc.getChannels())
				{
					if(channel2.getUser(sourceNick) != null)
					{
						channel2.removeUser(source);
					}
				}
				
				//Remove the user who quit from the users list
				irc.getUsers().remove(source);
			}
			else
			{
				//The bot disconnected
				irc.disconnect(false);
			}
		}
		else if(command.equals("KICK"))
		{
			//Someone is being kicked
			User recipient = irc.getUser(message);
			if(message.equalsIgnoreCase(irc.getNickname()))
			{
				//The bot just got kicked
				irc.getChannels().remove(channel);
			}
			else
			{
				//Someone else got kicked
				channel.removeUser(recipient);
			}
			irc.getEventListenerManager().dispatchEvent(new KickEvent(recipient, source, channel, parsedLine.get(2)));
		}
		else if(command.equals("MODE"))
		{
			//A mode is being set
			String mode = line.substring(line.indexOf(target, 2) + target.length() + 1);
			if(mode.startsWith(":"))
			{
				mode = mode.substring(1);
			}
			
			if(sourceNick.equals(target))
			{
				List<String> modeToParse = StringUtils.parseStringtoList(mode, " ");
				mode = modeToParse.get(modeToParse.size() - 1);
			}
			
			irc.getEventListenerManager().dispatchEvent(new ModeEvent(sourceNick, channel, mode));
			handleMode(sourceNick, target, mode);
		}
		else if(command.equals("TOPIC"))
		{
			//Someone is changing the topic
			long currentTime = System.currentTimeMillis();
			channel.setTopic(message);
			channel.setTopicSetter(sourceNick);
			channel.setTopicSetTimestamp(currentTime);
			
			irc.getEventListenerManager().dispatchEvent(new TopicEvent(sourceNick, channel, message));
		}
		else if(command.equals("INVITE"))
		{
			//Someone is inviting someone into the channel
			if(target.equalsIgnoreCase(irc.getNickname()))
			{
				//The bot is being invited. Join the channel.
				irc.cmdJOIN(message, "");
			}
			
			irc.getEventListenerManager().dispatchEvent(new InviteEvent(source, target, message));
		}
	}
	
	private void handleMode(String source, String target, String mode)
	{
		if(source.equals(target))
		{
			//This is a user mode.
			irc.parseUserModesChange(mode);
			return;
		}
		else
		{
			Channel channel = irc.getChannel(target);
			if(!mode.contains(" "))
			{
				//This mode change does not contain any arguments
				channel.parseModeChange(mode);
				return;
			}
			else
			{
				//This mode change contains arguments. Handle them one by one.
				List<String> params = StringUtils.parseStringtoList(mode, " ");
				char modeOperator = '+';
				int paramNumber = 1;

				for (int i = 0; i < params.get(0).length(); i++) 
				{
					char atPosition = params.get(0).charAt(i);
					if(atPosition == '+' || atPosition == '-')
					{
						modeOperator = atPosition;
					}
					else if(irc.getServerInfo().getUserPrefixes().containsKey(Character.toString(atPosition)))
					{
						//This mode is changing a user's access level in the channel
						User user = channel.getUser(params.get(paramNumber));
						channel.parseModeChangeOnUser(user, "" + modeOperator + atPosition);
						paramNumber++;
					}
					else if(irc.getServerInfo().getChannelListModes().contains(Character.toString(atPosition)))
					{
						//This mode is a list mode. It's not important to us. Skip to the next argument.
						paramNumber++;
					}
					else if(irc.getServerInfo().getChannelSetArgsModes().contains(Character.toString(atPosition)))
					{
						//This mode needs an argument to be set. Handle it in the channel.
						if(modeOperator == '+')
						{
							channel.getModes().put(Character.toString(atPosition), params.get(paramNumber));
						}
						else
						{
							channel.getModes().remove(Character.toString(atPosition));
						}
						paramNumber++;
					}
					else if(irc.getServerInfo().getChannelSetUnsetArgsModes().contains(Character.toString(atPosition)))
					{
						//This mode needs an argument to be set AND to be unset. Handle it in the channel.
						if(modeOperator == '+')
						{
							channel.getModes().put(Character.toString(atPosition), params.get(paramNumber));
						}
						else
						{
							channel.getModes().remove(Character.toString(atPosition));
						}
						paramNumber++;
					}
					else if(irc.getServerInfo().getChannelNoArgsModes().contains(Character.toString(atPosition)))
					{
						//This mode doesn't take arguments. Parse it normally.
						channel.parseModeChange("" + modeOperator + atPosition);
					}
				}
			}
		}
	}
	
	private List<String> tokenizeLine(String input)
	{
		List<String> retn = new ArrayList<String>();

		if (input == null || input.length() == 0)
			return retn;

		String temp = input;

		while (true)
		{
			if (temp.startsWith(":") && retn.size() > 0) 
			{
				retn.add(temp.substring(1));

				return retn;
			}

			String[] split = temp.split(" ", 2);
			retn.add(split[0]);

			if (split.length > 1)
				temp = split[1];
			else
				break;
		}

		return retn;
	}
	
	private LinkedHashMap<String, String> getUserPrefixes(String prefixString)
	{
		LinkedHashMap<String, String> prefixes = new LinkedHashMap<String, String>();
		char[] channelModes = prefixString.substring(1, prefixString.indexOf(")")).toCharArray();
		char[] userLevels = prefixString.substring(prefixString.indexOf(")") + 1).toCharArray();
		
		for(int i = 0; i < channelModes.length; i++)
		{
			prefixes.put("" + channelModes[i], "" + userLevels[i]);
		}
		return prefixes;
	}
	
	private LinkedHashMap<String, String> getReverseUserPrefixes(String prefixString)
	{
		LinkedHashMap<String, String> prefixes = new LinkedHashMap<String, String>();
		char[] channelModes = prefixString.substring(1, prefixString.indexOf(")")).toCharArray();
		char[] userLevels = prefixString.substring(prefixString.indexOf(")") + 1).toCharArray();
		
		for(int i = 0; i < channelModes.length; i++)
		{
			prefixes.put("" + userLevels[i], "" + channelModes[i]);
		}
		return prefixes;
	}
}