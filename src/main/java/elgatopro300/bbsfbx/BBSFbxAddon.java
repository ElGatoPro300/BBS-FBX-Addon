package elgatopro300.bbsfbx;

import mchorse.bbs_mod.addons.BBSAddon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BBSFbxAddon extends BBSAddon
{
    private static final Logger LOGGER = LoggerFactory.getLogger("BBS Fbx Addon");

    public BBSFbxAddon()
    {
        LOGGER.info("BBS Fbx Addon ready");
    }
}
