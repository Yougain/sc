#!/bin/env ruby


require 'Yk/path_aux'
require 'Yk/debug2'


if "/data/data/com.termux/files"._d?
	prefix = "/data/data/com.termux/files/usr"
else
	prefix = "/"
end

ESV = prefix / "etc" / "service"
VSV = prefix / "var" / "service"
ESP = prefix / "etc" / "service_pool"

class TimeDiffFmt
	TM = [:year, :mon, :day, :hour, :min, :sec]
	TMF = [ "%y-", "%m-", "%d %a ", "%H:", "%M:", "%S" ]
	t = Time.at(0)
	PADDS = []
	TMF.each do |f|
		PADDS.push " " * t.strftime(f).size
	end
	TMFL = [] # [21, 18, 15, 8, 5, 2]
	plen = 0
	PADDS.reverse_each do |pad|
		plen += pad.size
		TMFL.unshift plen
	end
	def initialize ref, now = Time.now
		@ref = ref
		@now = now
		TM.each_with_index do |gr, i|
			if @ref.method(gr).call != @now.method(gr).call
				@diffIndex = i
				break
			end
		end
		@tmff = []
		TM.size.times do |i|
			if i >= @diffIndex
				fmt = PADDS[@diffIndex ... i].join + TMF[i .. -1].join
			else
				fmt = ""
			end
			@tmff.push fmt
		end
		@size = TMFL[@diffIndex]
	end
	attr_reader :size, :diffIndex
	class FormatError < Exception
		def initialize t, s
			super "Format error. Cannot format '#{t}' to '#{TMF[s.diffIndex..-1].join('')}'."
		end
	end
	def fmt t, padd = false
		if !t
			return size * " "
		end
		TM.each_with_index do |gr, i|
			diff = @now.method(gr).call != t.method(gr).call
			if i < @diffIndex
				if diff
					raise FormatError.new(t, self)
				end
			else
				if padd
					if diff
						return t.strftime(@tmff[i])
					end
				else
					break
				end
			end
		end
		if padd
			size * " "
		else
			return t.strftime @tmff[@diffIndex]
		end
	end
end

class RSvc
	List = {}
	def self.emerge arg, **opts
		sp = (ESP / arg)._?._e?.__it
		vs = (VSV / "run")._?._e?.__it
		if !sp && !vs
			if opts[:err_exit]
				STDERR.write "Error: service, '#{ARGV[0]}', not found."
				exit 1
			end
			return nil
		else
			new arg
		end
	end
	def self.each
		if List.empty?
			ESP.each_entry do |f|
				List[_ = f.basename] ||= new _
			end
			VSV.each_entry do |d|
				if (r = d / "run")._e?
					List[_ = d.basename] ||= new _
				end
			end
		end
		p List
		List.values.each do |v|
			p v
			yield v
			p
		end
	end
	def self.print_stats *rsvcs
		if rsvcs.empty?
			p
			each do |e|
				p e
				e.get_stat
				p
			end
			p
			en = to_enum
			p
		else
			en = rsvcs
		end
		p
		name_fsz = en.map{ _1.name.size }.max
		pid_fsz = en.map{ _1.pid.to_s.size }.max
		secondsL = en.map{ _1.seconds }.reject{ !_1 }
		enabledL = en.map{ _1.enabled ? "enabled " : "disabled" }
		runL = en.map{
			if _1.pid
				if _1.seconds
					"started at "
				else
					"stopped at "
				end
			else
				    "error      "
			end
		}
		if pid_fsz > 0
			pidL = en.map{
				if _1.pid
					sprintf "%#{pid_fsz}d", _1.pid
				else
					" " * pid_fsz
				end
			}
		else
			pidL = []
		end
		if secondsL.size > 0
			tn = Time.now
			oldest = tn - secondsL.max
			df = TimeDiffFmt.new oldest, tn
			seconds_fsz = df.size
			startL = en.map{ df.fmt((tn - _1.seconds rescue nil)) }
		else
			startL = []
		end
		printf "%#{name_fsz}s %s %s %#{pid_fsz}d\n", "NAME", "        ", "           ", !pidL.empty? ? "PID" : ""
		zip enabledL, runL, startL, pidL do |e, enabled, run, start, pid|
			printf "%#{name_fsz}s %s %s %s\n", e.name, enabled, run, start.to_s, pid.to_s
		end
	end
	def initialize name
		@name = name
	end
	attr_reader :pid, :seconds, :enabled
	def anal_stat ln
		lna = ln.split
		case lna[0]
		when "run:"
			@pid = lna[3].chop.to_i
			@seconds = lna[4].chop.to_i
			@enabled = true
		when "down:"
			@pid = nil
			@seconds = lna[3].chop.to_i
			@enabled = true
		when "fail:"
			@pid = nil
			@seconds = nil
			@enabled = nil
		else
			raise "Unknown stat: '#{lna[0]}'"
		end
	end
	def get_stat
		if (VSV / "@name" / "run")._e?
			(%W{sv status} + [ESV / "@name"]).read_each_line_p do |ln|
				p @name, ln
				sln, lln, = ln.split /;/
				anal_stat sln
				if lln
					@logger = RSvc.new @name / "log"
					@logger.anal_stat lln
				end
			end
		elsif (ESP / "@name")._e?
			@enabled = false
		else
			STDERR.write "Error: service, '#{@name}' not found."
			exit 1
		end
	end
	def print_stat
		arr = [self]
		@logger && arr.push(@logger)
		self.class.print_stats *arr
		if @pid
			%W{sudo lsp -3 #{@pid}}.system
		end
		if @logger
			if _ = [VSV / "log", "/var/log" / @name].detect{ _1 / "current"}._e?
				"tail".system "-10", _
			end
		end
	end
	def stat
		print_stat
	end
	def status
		print_stat
	end
	
end

require "fileutils"

[ESV, VSV, ESP].each{ FileUtils.mkdir_p _1 }


cmd = nil
target = nil

if ARGV.size > 2
	
end

CMDS = %W{add delete enable disable start stop status stat to_pool}


case ARGV.size
when 0
	RSvc.print_stats
when 1
	RSvc.emerge(ARGV[0], err_exit:1)&.print_stat(:long)
when 2
	ARGV.each do |e|
		if CMDS.include? e
			if !cmd
				cmd = e
			else
				target = e
			end
		else
			target = e
		end
	end
	RSvc.emerge(target, err_exit:1)&.method(cmd).call
else
	STDERR.write <<~END
		usage: #{$0.basename} #{CMDS.join('|')} SERVICE_NAME
	END
end



exit 0





