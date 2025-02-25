function after(hook, param)
	local ret = param:getResult()
	if ret == nil then
		ret = -1
	end

	local fake = 2
	local setting = param:getSettingReMap("gsm.data.state", "phone.datastate")
	if setting == nil then
	    return false
	end

	if tonumber(setting) ~= nil then
		fake = tonumber(setting)
	else
		if ret == fake then
			fake = -1
		end
	end

	--DATA_DISCONNECTED=0
	--DATA_DISCONNECTING=4
	--DATA_ENABLED_REASON_CARRIER=2
	--DATA_ENABLED_REASON_OVERRIDE=4
	--DATA_ENABLED_REASON_POLICY=1
	--DATA_ENABLED_REASON_THERMAL=3
	--DATA_ENABLED_REASON_UNKNOWN/DATA_UNKNOWN=-1
	--DATA_ENABLED_REASON_USER=0
	--DATA_HANDOVER_IN_PROGRESS=5
	--DATA_SUSPENDED=3
	--

	param:setResult(fake)
	return true, tostring(ret), tostring(fake)
end