function after(hook, param)
	local res = param:getResult()
	if res == nil then
	    return false
	end

	local fake = param:getSetting("zone.country", "Iceland")
	if fake == nil then
	    return false
	end

	param:setResult(fake)
	return true, res, fake
end