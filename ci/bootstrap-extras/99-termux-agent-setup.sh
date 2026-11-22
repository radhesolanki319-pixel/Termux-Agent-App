# Termux Agent App: one-time first-run setup launcher.
# Runs via etc/profile.d on every login shell; the marker check makes it
# a no-op after the first successful setup.
if [ ! -e "$HOME/.termux-agent-setup.done" ] && [ -f "$PREFIX/etc/termux-agent/first-setup.sh" ]; then
	bash "$PREFIX/etc/termux-agent/first-setup.sh"
fi
