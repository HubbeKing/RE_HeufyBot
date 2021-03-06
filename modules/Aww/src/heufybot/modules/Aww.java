package heufybot.modules;

import heufybot.utils.FileUtils;
import heufybot.utils.URLUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.util.HashMap;
import java.util.List;

public class Aww extends Module
{
    private final String clientIDPath = "data/imgurclientid.txt";

    public Aww(String server)
    {
        super(server);

        this.authType = AuthType.Anyone;
        this.apiVersion = 60;
        this.triggerTypes = new TriggerType[] { TriggerType.Message };
        this.trigger = "^" + this.commandPrefix + "(aww)($)";
    }

    @Override
    public void processEvent(String source, String message, String triggerUser, List<String> params)
    {
        try
        {
            if (FileUtils.readFile(this.clientIDPath).equals(""))
            {
                this.bot.getServer(this.server).cmdPRIVMSG(source, "No Imgur client ID found.");
                return;
            }

            int pageNumber = (int) (Math.random() * 100 + 1);
            JSONArray dataArray = (JSONArray) this.getJSON(
                    "https://api.imgur.com/3/gallery/r/aww/time/all/" + pageNumber).get("data");
            JSONObject object = (JSONObject) dataArray
                    .get((int) (Math.random() * dataArray.size()));

            String title = object.get("title").toString();
            String url = object.get("link").toString();

            if (url.equals(""))
            {
                this.bot.getServer(this.server)
                        .cmdPRIVMSG(source,
                                "Something went wrong while trying to get an image. Most likely the Imgur API is down" +
                                        ".");
                return;
            }

            this.bot.getServer(this.server).cmdPRIVMSG(source, title + " | " + url);
        }
        catch (ParseException e)
        {
            this.bot.getServer(this.server).cmdPRIVMSG(source,
                    "Something went wrong while trying to read the data.");
        }
    }

    private JSONObject getJSON(String urlString) throws ParseException
    {
        HashMap<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Client-ID "
                + FileUtils.readFile(this.clientIDPath).replaceAll("\n", ""));
        return (JSONObject) new JSONParser().parse(URLUtils.grab(urlString, headers));
    }

    @Override
    public String getHelp(String message)
    {
        return "Commands: " + this.commandPrefix
                + "aww | Returns random cuteness from the /r/aww subreddit.";
    }

    @Override
    public void onLoad()
    {
        FileUtils.touchFile(this.clientIDPath);
    }

    @Override
    public void onUnload()
    {
    }
}
