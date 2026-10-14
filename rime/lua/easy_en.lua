local is_split_sentence
local wordninja_split

local function capture(cmd)
   local f = assert(io.popen(cmd, 'r'))
   local s = assert(f:read('*a'))
   f:close()
   return s
end

local function memory_callback(memory, commit)
   for i, dictentry in ipairs(commit:get()) do
      memory:update_userdict(dictentry, 1, "")
   end
end

local function init(env)
   env.mem = Memory(env.engine,env.engine.schema)
   env.mem:memorize(function(commit) memory_callback(env.mem, commit) end)

   is_split_sentence = env.engine.schema.config:get_bool('easy_en/split_sentence')
   if not is_split_sentence then
      wordninja_split = function(sentence)
         return sentence
      end
      return
   end

   local use_wordninja_rs_lua_module = env.engine.schema.config:get_bool('easy_en/use_wordninja_rs_lua_module')
   local use_wordninja_rs = env.engine.schema.config:get_bool('easy_en/use_wordninja_rs')
   local use_wordninja_py = env.engine.schema.config:get_bool('easy_en/use_wordninja_py')
   if (not use_wordninja_rs_lua_module) and (not use_wordninja_rs) and (not use_wordninja_py) then
      -- default use wordninja_rs_lua_module
      use_wordninja_rs_lua_module = true
   end

   if use_wordninja_rs_lua_module then
      local wordninja_rs_lua_module_path = env.engine.schema.config:get_string('easy_en/wordninja_rs_lua_module_path')
      if not string.find(package.cpath, wordninja_rs_lua_module_path, 1, true) then
         package.cpath = package.cpath .. ";" .. wordninja_rs_lua_module_path
      end
      -- the wordninja native module is not shipped on all platforms;
      -- fall back to identity so a missing module can never kill the filter
      local ok, mod = pcall(require, "wordninja")
      if ok and mod and mod.split then
         wordninja_split = mod.split
      else
         wordninja_split = function(sentence)
            return sentence
         end
      end
      return
   end

   if use_wordninja_rs then
      local wordninja_rs_path = env.engine.schema.config:get_string('easy_en/wordninja_rs_path')
      wordninja_split = function(sentence)
         return capture(wordninja_rs_path .. " -n '" .. sentence .. "'")
      end
      return
   end

   if use_wordninja_py then
      wordninja_split = function(sentence)
         return capture([[python -c "import sys; import wordninja; sys.stdout.write(' '.join(wordninja.split(']] .. sentence .. [[')))"]])
      end
      return
   end
end

local function fini(env)
   env.mem:disconnect()
end

local function new_dict_cand(mem, start, _end, text, custom_code)
   dictentry = DictEntry()
   dictentry.text = text
   dictentry.custom_code = custom_code
   ph = Phrase(mem, "word", start, _end, dictentry)

   return ShadowCandidate(ph:toCandidate(), "word", text .. " ", "📝", "")
end

local function is_in_dict(mem, text)
   return mem:user_lookup(text, false) or mem:dict_lookup(text, false, 1)
end

-- typo correction (edit distance 1), easy_en only.
-- table_translator does exact-prefix trie lookup, so a mistyped word such
-- as "hsppy" yields zero candidates. when the translator produced nothing
-- matching the raw input, generate every one-edit variant and keep the ones
-- that really exist in the dictionary. guarded to stay cheap:
--   * only fires when input is a plain lowercase-able word of length >= 4
--   * never fires when the input itself is already a dictionary word
--   * results are memoized per input string and capped in count
local FUZZY_MIN_LEN = 4
local FUZZY_MAX_RESULTS = 5
local FUZZY_CACHE_LIMIT = 500
local FUZZY_ALPHABET = "abcdefghijklmnopqrstuvwxyz"
local fuzzy_cache = {}
local fuzzy_cache_size = 0

local function fuzzy_variants(s)
   local out, seen = {}, {}
   local n = #s
   local function add(w)
      if w ~= s and not seen[w] then
         seen[w] = true
         out[#out + 1] = w
      end
   end
   -- transpositions first (small set, very common slip)
   for i = 1, n - 1 do
      add(s:sub(1, i - 1) .. s:sub(i + 1, i + 1) .. s:sub(i, i) .. s:sub(i + 2))
   end
   -- deletions (extra letter typed)
   for i = 1, n do
      add(s:sub(1, i - 1) .. s:sub(i + 1))
   end
   -- substitutions (wrong letter typed)
   for i = 1, n do
      local pre, post, orig = s:sub(1, i - 1), s:sub(i + 1), s:sub(i, i)
      for j = 1, 26 do
         local c = FUZZY_ALPHABET:sub(j, j)
         if c ~= orig then
            add(pre .. c .. post)
         end
      end
   end
   -- insertions (missing letter); largest set, checked last
   for i = 1, n + 1 do
      local pre, post = s:sub(1, i - 1), s:sub(i)
      for j = 1, 26 do
         add(pre .. FUZZY_ALPHABET:sub(j, j) .. post)
      end
   end
   return out
end

local function fuzzy_correct(mem, s)
   local cached = fuzzy_cache[s]
   if cached then
      return cached
   end
   local out = {}
   for _, v in ipairs(fuzzy_variants(s)) do
      if #out >= FUZZY_MAX_RESULTS then
         break
      end
      if is_in_dict(mem, v) then
         out[#out + 1] = v
      end
   end
   if fuzzy_cache_size >= FUZZY_CACHE_LIMIT then
      fuzzy_cache, fuzzy_cache_size = {}, 0
   end
   fuzzy_cache[s] = out
   fuzzy_cache_size = fuzzy_cache_size + 1
   return out
end

-- span for an injected correction: the correction always covers the whole
-- raw input, so anchor it at 0..#raw_input rather than at any sub-segment a
-- fragmented lookup may have produced (that would replace only the tail and
-- leave garbage like "hsphappy" behind).
local function correction_span(raw_input)
   return 0, #raw_input
end

local function enhance_filter(input, env)
   local seen = {}
   local has_match = false
   local raw_input = nil
   local ok_input, ctx_input = pcall(function() return env.engine.context.input end)
   if ok_input and type(ctx_input) == "string" and #ctx_input > 0 then
      raw_input = ctx_input
   end
   local probe = raw_input and raw_input:lower() or nil
   for cand in input:iter() do
      if (cand.comment:find("☯")) then
         -- show splited sentence
         -- don't need to save splited sentence into dict
         if (is_split_sentence) then
            sentence = wordninja_split(cand.text)
            lower_sentence = string.lower(sentence)

            if (not (lower_sentence == sentence)) then
               yield(Candidate("sentence", cand.start, cand._end, lower_sentence .. " ", "💡"))
            end

            yield(Candidate("sentence", cand.start, cand._end, sentence .. " ", "💡"))
         end

         composition = env.engine.context.composition
         segmentation = composition:toSegmentation()
         custom_code = segmentation.input

         if (not (cand.text == custom_code)) then
            yield(new_dict_cand(env.mem, cand.start, cand._end, custom_code, custom_code))
         end
         yield(new_dict_cand(env.mem, cand.start, cand._end, cand.text, custom_code))
      else
         yield(UniquifiedCandidate(cand, "word", cand.text .. " ", cand.comment))
      end
      local t = cand.text
      seen[t] = true
      -- only a candidate STRICTLY LONGER than the probe counts as "the
      -- translator already continues this input". the raw-input echo has
      -- exactly the probe's length and must not block typo correction.
      if probe and #t > #probe and t:sub(1, #probe):lower() == probe then
         has_match = true
      end
   end
   -- typo correction pass: only when the translator produced nothing that
   -- continues the raw input, the input is a plain word, and it is not
   -- already a dictionary word itself (avoids noise on correct words).
   if probe and not has_match and #probe >= FUZZY_MIN_LEN
      and probe:match("^[a-z]+$") and not is_in_dict(env.mem, probe) then
      local seg_start, seg_end = correction_span(raw_input)
      local found = fuzzy_correct(env.mem, probe)
      if #found > 0 and log then
         -- one breadcrumb per fresh computation (memoized repeats stay quiet
         -- except this line, which fires per update; remove once confirmed)
         log.info("[easy_en] fuzzy(" .. probe .. ") -> " .. table.concat(found, ","))
      end
      for _, w in ipairs(found) do
         if not seen[w] then
            seen[w] = true
            yield(new_dict_cand(env.mem, seg_start, seg_end, w, w))
         end
      end
   end
end

return { enhance_filter = { init = init, fini = fini, func = enhance_filter} }
