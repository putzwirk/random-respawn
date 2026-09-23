package com.neuromuser.randomrespawn;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Config {
    public boolean defaultEnabled = true;
    public int respawnRange = 10000;
    public boolean setDayTimeOnPlayerDeath = false;
    public Map<String, Boolean> playerSettings = new HashMap<>();
    public Set<String> pendingRespawns = new HashSet<>();
}
