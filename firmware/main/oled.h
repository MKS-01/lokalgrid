/* Lokalgrid — the 1.3" display.
 *
 * The point of this screen (§1): **the node is usable with zero phones
 * connected.** Roster, queue depth, duty used, hours left — pages cycled by the
 * user button, later. What it earns today is the difference between a dev board
 * with a blinking LED and something you can put on a table and read.
 */
#pragma once

#include <stdbool.h>
#include <stdint.h>

/** Attach to whichever bus the scan found the display on. Safe to call when
 *  there is no display — it says so and does nothing after that. */
bool oled_init(uint8_t i2c_port);

/** Replace the whole screen with six lines of text, top to bottom. NULL or a
 *  short array is fine; missing lines are left blank. 21 characters fit.
 *  Line 0 is drawn as an inverted header bar. */
void oled_lines(const char *const *lines, uint8_t count);

/** As above, with one short string right-aligned in the header bar — the single
 *  figure worth reading from across a table. Dropped rather than overlapped if
 *  the title leaves no room for it. */
void oled_lines_badge(const char *const *lines, uint8_t count, const char *badge);

/** The boot page, drawn before the network is up so the screen is never blank
 *  while something is still happening. */
void oled_splash(const char *detail);
