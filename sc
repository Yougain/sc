#!/bin/env ruby
#

require 'Yk/path_aux'
require 'Yk/debug2'
#$
require 'Yk/escseq'
Escseq.beIncludedBy String
#
if "/data/data/com.termux/files"._d?
	prefix = "/data/data/com.termux/files/usr"
else
	prefix = "/"
end

ESV = prefix / "etc" / "service"
VSV = prefix / "var" / "service"
ESP = prefix / "etc" / "service_pool"
LOGD2 = prefix / "var/log/sv"
LOGGER = prefix / "usr/bin" / "svlogger"

if !LOGGER._e?
	LOGGER.write <<~END
		#!/bin/env sh
		# Get the name of the service from the PWD, this assumes the name of the
		# service is one level above the log directory.
		pwd=${PWD%/*} # $SVDIR/service/foo/log
		service=${pwd##*/} # foo

		if [ -e /data/data/com.termux/files ];then
		        LOGDIR=/data/data/com.termux/files/usr/var/log
		        SUDO=
		else
		        LOGDIR=/var/log
		        SUDO=sudo
		fi

		if [ ! -d "$LOGDIR/sv/$service" ];then
		        $SUDO mkdir -p "$LOGDIR/sv/$service"
		fi

		exec svlogd -ttt "$LOGDIR/sv/$service"
	END
	LOGGER.chmod 0775
end

if !LOGD2._e?
	LOGD2.mkdir_p
end


def getTai64NSec (d)
    if d =~ /\@[0-9a-f]{24}/
        str = $&
        content = $'
        sec = str.slice(2, 15)
        nanosec = str.slice(17..23)
        if str[1].chr.to_i >= 4
            sec = (4 - str[1].chr.to_i).to_s + sec
        end
        low = sec.slice(8...16).to_i(16)
        up = sec.slice(0...8).to_i(16)
        sub = nanosec.to_i(16)
        Time.at(low - 10, sub.to_f / 1000 * 16)
    else
        nil
    end
end


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
			return " " * size
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
		vs = (VSV / arg / "run")._?._e?.__it
		if !sp && !vs
			if opts[:err_exit]
				STDERR.write "Error: service, '#{ARGV[0]}', not found.\n"
				exit 1
			end
			return nil
		else
			new arg
		end
	end
	def self.getList
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
		List.values.sort_by{ _1. name }
	end
	def self.print_stats *rsvcs
		if rsvcs.empty?
			en = getList
			en.each do |e|
				e.get_stat
			end
		else
			en = rsvcs
		end
		name_fsz = en.map{ _1.name.size }.max
		pid_fsz = en.map{ _1.pid.to_s.size }.max
		pid_fsz = pid_fsz > 0 ? [3, pid_fsz].max : 0
		secondsL = en.map{ _1.seconds }.reject{ !_1 }
		enabledL = en.map{ _1.enabled ? "enabled" : "disabled" }
		if en != rsvcs
			loggerLS = en.map{ _1.logger&.pid&.to_s&.size }
			loggerL = en.map{ "#{_1.logger&.pid}" }
			logger_fsz = clause loggerLS.reject{ _1.nil? } do
				_1.empty? ? 0 : (_1 + [3]).max
			end
		else
			loggerL = []
			logger_fsz = 0
		end
		enabled_fsz = enabledL.map{ _1.size }.max
		runL = en.map{
			if _1.pid
				if _1.seconds
					"started".green + " at "
				end
			else
				if _1.seconds
					"stopped".red + " at "
				else
				    "error      ".red
				end
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
			start_fsz = df.size
			startL = en.map{ df.fmt((tn - _1.seconds rescue nil)) }
		else
			startL = []
		end
		print sprintf("%#{name_fsz}s %#{enabled_fsz}s %11s%#{start_fsz}s %-#{pid_fsz}s %-#{logger_fsz}s\n", "NAME", "", "", "", !pidL.empty? ? "PID" : "", logger_fsz == 0 ? "" : "LOG").yellow
		en.zip enabledL, runL, startL, pidL, loggerL do |e, enabled, run, start, pid, logger|
			printf "%#{name_fsz}s %#{enabled_fsz}s %s%s %s %s\n", e.name, enabled == "enabled" ? enabled.cyan : enabled.red, run, start.to_s, pid.to_s, logger.to_s
		end
	end
	def initialize name
		@name = name
	end
	attr_reader :pid, :seconds, :enabled, :name, :logger
	def anal_stat ln
		lna = ln.split
		case lna[0]
		when "run:"
			@pid = lna[3].chop.to_i
			@seconds = lna[4].chop.to_i
			@enabled = true
		when "down:"
			@pid = nil
			@seconds = lna[2].chop.to_i
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
		if (VSV / @name / "run")._e?
			(%W{sv status} + [VSV / @name]).read_each_line_p do |ln|
				sln, lln, = ln.split /;/
				anal_stat sln
				if lln
					@logger = RSvc.new @name / "log"
					@logger.anal_stat lln
				end
			end
		elsif (ESP / @name)._e?
			@enabled = false
		else
			STDERR.write "Error: service, '#{@name}' not found.\n"
			exit 1
		end
	end
	def print_stat
		get_stat
		arr = [self]
		@logger && arr.push(@logger)
		self.class.print_stats *arr
		if @pid
			print "\n"
			tz = ENV["TZ"]
			"sudo".system "bash", "-c", "TZ=#{tz} lsp -1 #{@pid.to_s}"
		end
		if @logger
			print "\n"
			if _ = [VSV / @name / "log", LOGD2 / @name, "/var/log" / @name, ].detect{ (_1 / "current")._e? }
				lns = []
				%W{tail -10 #{_ / "current"}}.read_each_line_p do |ln|
					if ln =~ /^(\d\d\d\d)\-(\d\d)\-(\d\d)(T|_)(\d\d):(\d\d):(\d\d)\.(\d+)/
						content = $'
						arr = [$1, $2, $3, $5, $6, $7, $8]
				        if arr[6].size < 6
				            arr[6] += "0" * (6 - arr[6].size)
				        elsif arr[].size > 6
				            arr[6] = arr[6][0..5]
				        end
				        t = Time.utc(*arr).localtime
				        lns.push [t, content]
					elsif ln =~ /^\s*(\@[0-9a-f]{24})(\s|$)/
						content = $'
						t = getTai64NSec($1)
				        lns.push [t, content]
					else
						print ln
					end
				end
				if !lns.empty?
					oldest = lns.min_by{_1[0]}
					df = TimeDiffFmt.new oldest[0], Time.now
					lns.each do
						print df.fmt(_1[0]) + _1[1]
					end
				end
			end
		end
	end
	def stat
		print_stat
	end
	def status
		print_stat
	end
	def stop
		%W{sv stop #{@name}}.system
		if [VSV, ESV].detect{(_1 / @name / "log")._e?}
			%W{sv stop #{@name}/log}.system
		end
	end
	def start
		if [VSV, ESV].detect{(_1 / @name / "log")._e?}
			%W{sv start #{@name}/log}.system
		end
		%W{sv start #{@name}}.system
	end
	def delete
		disable
	end
	def del
		disable
	end
	def add
		enable
	end
	def disable
		stop
		vd = VSV / @name
		ed = ESV / @name
		erun = ed / "run"
		prog = ESP / @name
		if vd._e?
			if !vd._L?
				vd.mv ed
			else
				vd.rm
			end
			if !erun._L?
				erun.mv prog
				prog.symlink erun
			end
			edl = ESV / @name / "log"
			edl2 = LOGD2 / @name
			erunl = edl / "run"
			if ! erunl._L?
				erunl.rm
				LOGGER.symlink erunl
			end
			if (edl / "current")._e?
				edl2.mkdir_p
				edl.each_entry do |f|
					if f.basename =~ /^@[0-9A-Fa-f]{24}\.(u|s)$/ || f.basename == "current"
						f.mv edl2
					end
				end
			end
		end
	end
	def enable
		vd = VSV / @name
		ed = ESV / @name
		if vd._L?
			STDERR.write "Error: Already enabled.".ln
			exit 1
		elsif vd._e?
			STDERR.write "Error: Not converted to service_pool style.".ln
			exit 1
		else
			ed.symlink vd
			start
		end
	end
end

require "fileutils"

[ESV, VSV, ESP].each{ FileUtils.mkdir_p _1 }


cmd = nil
target = nil

if ARGV.size > 2
	
end

CMDS = %W{add delete enable disable start stop status stat}

def usage
	STDERR.write <<~END
		usage: #{$0.basename} #{CMDS.join('|')} SERVICE_NAME
	END
end

case ARGV.size
when 0
	RSvc.print_stats
when 1
	RSvc.emerge(ARGV[0], err_exit:1)&.print_stat
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
	if CMDS.detect cmd
		RSvc.emerge(target, err_exit:1)&.method(cmd).call
	else
		usage
	end
else
	usage
end



exit 0





