---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by zjzjhd.
--- DateTime: 2023/2/26 20:52
---
if (redis.call('get', KEYS[1]) == ARGV[1]) then
    return redis.call('del', KEYS[1])
end
return 0