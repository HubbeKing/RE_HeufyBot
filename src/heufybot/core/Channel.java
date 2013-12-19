package heufybot.core;

import java.util.HashMap;
import java.util.Set;

public class Channel
{
	private String name;
	private HashMap<User, String> usersInChannel;
	private String topic;
	private String topicSetter;
	private long topicSetTimestamp;
	private HashMap<String, String> modes;
	
	public Channel(String name)
	{
		this.name = name;
		this.usersInChannel = new HashMap<User, String>();
		this.topic = "";
		this.topicSetter = "";
		this.modes = new HashMap<String, String>();
	}
	
	public String getName()
	{
		return name;
	}
	
	public void parseModeChange(String modeChange)
	{
		boolean adding = true;
		for (char curChar : modeChange.toCharArray())
		{
			if (curChar == '-')
			{
				adding = false;
			}
			else if (curChar == '+')
			{
				adding = true;
			}
			else if (adding)
			{
				String current = Character.toString(curChar);
				if(!modes.containsKey(current))
				{
					modes.put(current, "");
				}
			}
			else
			{
				String current = Character.toString(curChar);
				if(modes.containsKey(current))
				{
					modes.remove(current);
				}
			}
		}
	}
	
	public void parseModeChangeOnUser(User user, String modeChange)
	{
		String modesOnUser = usersInChannel.get(user);
		
		boolean adding = true;
		for (char curChar : modeChange.toCharArray())
		{
			if (curChar == '-')
			{
				adding = false;
			}
			else if (curChar == '+')
			{
				adding = true;
			}
			else if (adding)
			{
				if(!modesOnUser.contains(Character.toString(curChar)))
				{
					modesOnUser += curChar;
				}
			}
			else
			{
				String parsedString = Character.toString(curChar);
				modesOnUser = modesOnUser.replace(parsedString, "");
			}
		}
		usersInChannel.put(user, modesOnUser);
	}
	
	public void addUser(User user)
	{
		usersInChannel.put(user, "");
	}
	
	public void removeUser(User user)
	{
		usersInChannel.remove(user);
	}
	
	public String getModesOnUser(User user)
	{
		return usersInChannel.get(user);
	}
	
	public User[] getUsers()
	{
		Set<User> userSet = usersInChannel.keySet();
		User[] userArray = new User[userSet.size()];
		return userSet.toArray(userArray);
	}
	
	public User getUser(String nickname)
	{
		for(User user : usersInChannel.keySet())
		{
			if(user.getNickname().equalsIgnoreCase(nickname))
			{
				return user;
			}
		}
		return null;
	}

	public String getTopic()
	{
		return topic;
	}

	public void setTopic(String topic) 
	{
		this.topic = topic;
	}

	public String getTopicSetter() 
	{
		return topicSetter;
	}

	public void setTopicSetter(String topicSetter) 
	{
		this.topicSetter = topicSetter;
	}

	public long getTopicSetTimestamp() 
	{
		return topicSetTimestamp;
	}

	public void setTopicSetTimestamp(long topicSetTimestamp) 
	{
		this.topicSetTimestamp = topicSetTimestamp;
	}

	public HashMap<String, String> getModes() 
	{
		return modes;
	}
	
	public boolean checkOpStatus(User user)
	{
		String modes = usersInChannel.get(user);
		ServerInfo info = ServerInfo.getInstance();
		
		for(char modeChar : modes.toCharArray())
		{
			if(modeChar != 'v' && info.getUserPrefixes().containsKey(Character.toString(modeChar)))
			{
				return true;
			}
		}
		return false;
	}
}