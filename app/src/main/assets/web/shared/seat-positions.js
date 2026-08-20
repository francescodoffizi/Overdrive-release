/*
 * Seat positions page.
 *
 * Manages OverDrive's own store of driver seat + mirror geometry: the positions
 * captured from the car's own memory slots (by holding a position button in the car)
 * plus any the user saves here.
 *
 * Two deliberate absences:
 *
 *  - No axis editing. Positions are captured, never typed. A hand-entered axis value
 *    is a seat pose nobody chose; you pose the seat with the physical controls and
 *    then save what the car is in.
 *  - No automation editing. Automations live on the Automations page. This page only
 *    READS them, so it can show which positions are depended on and refuse to let a
 *    delete or an overwrite break something silently.
 */
const SeatPositions = {
    positions: [],
    automations: {},
    current: null,
    acc: false,
    movementBlocked: false,
    modelId: null,
    modelConfirmed: false,
    modelAcknowledged: false,
    appliedId: null,
    palette: [],
    colourMax: 30,
    _pollTimer: null,

    // Axis table. Order here is the display order. Groups match the two batches
    // applyFull writes, which is also how a person thinks about them.
    AXES: [
        { key: 'HORIZONTAL', i18n: 'seatpos.axis_horizontal', group: 'seat' },
        { key: 'BACKREST',   i18n: 'seatpos.axis_backrest',   group: 'seat' },
        { key: 'HEIGHT',     i18n: 'seatpos.axis_height',     group: 'seat' },
        { key: 'SITPOINT',   i18n: 'seatpos.axis_sitpoint',   group: 'seat' },
        { key: 'LEGHOLDER',  i18n: 'seatpos.axis_legholder',  group: 'seat' },
        { key: 'HEADREST_H', i18n: 'seatpos.axis_headrest_h', group: 'seat' },
        { key: 'HEADREST_V', i18n: 'seatpos.axis_headrest_v', group: 'seat' },
        { key: 'LEFT_H',     i18n: 'seatpos.axis_left_h',     group: 'mirror' },
        { key: 'LEFT_V',     i18n: 'seatpos.axis_left_v',     group: 'mirror' },
        { key: 'RIGHT_H',    i18n: 'seatpos.axis_right_h',    group: 'mirror' },
        { key: 'RIGHT_V',    i18n: 'seatpos.axis_right_v',    group: 'mirror' },
        { key: 'ST_H',       i18n: 'seatpos.axis_st_h',       group: 'mirror' },
        { key: 'ST_V',       i18n: 'seatpos.axis_st_v',       group: 'mirror' }
    ],

    // An axis the car does not have reads NaN and is stored as this sentinel, so an
    // apply round-trips faithfully. Never show it — it is not a value, it is an absence.
    SENTINEL: 127.5,

    ICONS: {
        bolt: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>',
        dots: '<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><circle cx="12" cy="5" r="1.8"/><circle cx="12" cy="12" r="1.8"/><circle cx="12" cy="19" r="1.8"/></svg>',
        link: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/></svg>',
        pencil: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 20h9"/><path d="M16.5 3.5a2.12 2.12 0 0 1 3 3L7 19l-4 1 1-4z"/></svg>',
        undo: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7v6h6"/><path d="M3.5 13a9 9 0 1 0 2.1-5.7L3 10"/></svg>',
        trash: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>'
    },

    init() {
        this.load();
        document.getElementById('spSaveNew').addEventListener('click', () => this.saveAsNew());
        document.getElementById('spList').addEventListener('click', (e) => this.onListClick(e));
        // Close any open row menu on an outside click.
        document.addEventListener('click', (e) => {
            if (!e.target.closest || !e.target.closest('.sp-menu-wrap')) this.closeMenus(null);
        });
        // Colour readout while dragging. Delegated because the dialog's contents are rebuilt
        // each time it opens, so a listener bound at render time would go stale.
        document.getElementById('spDialogHtml').addEventListener('input', (e) => {
            if (!e.target || e.target.id !== 'spColour') return;
            const num = document.getElementById('spColourNum');
            if (num) num.textContent = e.target.value;
        });
        // Poll the live geometry so "pose the seat, then save" works without a manual
        // refresh. Only while the tab is visible and only while ACC is on — with ACC off
        // the geometry cannot change, and every poll is 13 BYD reads.
        document.addEventListener('visibilitychange', () => this.syncPolling());
        this.syncPolling();
    },

    t(key, fallback) {
        try {
            const s = BYD.i18n.t(key);
            return (s && s !== key) ? s : (fallback || key);
        } catch (e) {
            return fallback || key;
        }
    },

    esc(v) {
        if (window.BYD && BYD.core && BYD.core._esc) return BYD.core._esc(String(v));
        const d = document.createElement('div');
        d.textContent = String(v);
        return d.innerHTML;
    },

    syncPolling() {
        clearInterval(this._pollTimer);
        this._pollTimer = null;
        if (document.hidden || !this.acc) return;
        this._pollTimer = setInterval(() => this.loadCurrent(), 5000);
    },

    // ── data ────────────────────────────────────────────────────────────────────

    async load() {
        try {
            const r = await fetch('/api/positions', { cache: 'no-store' });
            const j = await r.json();
            this.positions = Array.isArray(j.positions) ? j.positions : [];
            this.acc = !!j.acc;
            this.movementBlocked = !!j.movementBlocked;
            this.modelId = j.modelId || null;
            this.modelConfirmed = !!j.modelConfirmed;
            this.modelAcknowledged = !!j.modelAcknowledged;
        } catch (e) {
            this.positions = [];
        }
        await this.loadAutomations();
        await this.loadCurrent();
        this.render();
        this.syncPolling();
    },

    // Read-only. Used purely to tell the user what depends on a position before they
    // overwrite or delete it.
    async loadAutomations() {
        try {
            const r = await fetch('/api/automations', { cache: 'no-store' });
            const j = await r.json();
            this.automations = (j && typeof j === 'object') ? j : {};
        } catch (e) {
            this.automations = {};
        }
    },

    async loadCurrent() {
        try {
            const r = await fetch('/api/positions/current', { cache: 'no-store' });
            const j = await r.json();
            this.current = (j && j.axes) ? j.axes : null;
            this.currentAmbient = (j && j.ambient) ? j.ambient : null;
            // The swatch list is static but the BOUND is a HAL read and differs by trim, so
            // the picker offers what this car has rather than what the table happens to hold.
            if (Array.isArray(j && j.ambientPalette)) this.palette = j.ambientPalette;
            if (j && j.ambientColourMax) this.colourMax = j.ambientColourMax;
        } catch (e) {
            this.current = null;
            this.currentAmbient = null;
        }
        this.renderCurrent();
    },

    /**
     * Automations referencing a position id. Actions nest (If, Loop, action groups),
     * so walk any nested arrays rather than only the top level — an action buried in
     * an If branch still breaks when the position goes away.
     */
    usedBy(id) {
        const hits = [];
        const walk = (node) => {
            if (!node || typeof node !== 'object') return false;
            if (Array.isArray(node)) return node.map(walk).some(Boolean);
            let found = false;
            if (node.type === 'applySeatPosition' && node.variables && node.variables.id === id) found = true;
            for (const k of Object.keys(node)) {
                const v = node[k];
                if (v && typeof v === 'object' && walk(v)) found = true;
            }
            return found;
        };
        for (const key of Object.keys(this.automations || {})) {
            const a = this.automations[key];
            if (walk(a && a.actions)) hits.push({ id: key, name: (a && a.name) || key });
        }
        return hits;
    },

    // ── rendering ───────────────────────────────────────────────────────────────

    equipped(axes) {
        return this.AXES.filter(a => axes && axes[a.key] !== undefined && axes[a.key] !== this.SENTINEL);
    },

    axesHtml(axes) {
        const eq = this.equipped(axes);
        return ['seat', 'mirror'].map(group => {
            const items = eq.filter(a => a.group === group);
            if (!items.length) return '';
            const label = this.t(group === 'seat' ? 'seatpos.group_seat' : 'seatpos.group_mirrors',
                                 group === 'seat' ? 'Seat' : 'Mirrors');
            return '<div class="sp-axis-line"><span class="sp-axis-group">' + this.esc(label) + '</span>' +
                items.map(a =>
                    '<span class="sp-axis"><span class="k">' + this.esc(this.t(a.i18n, a.key)) +
                    '</span> <span class="v">' + this.esc(axes[a.key]) + '</span></span>').join('') +
                '</div>';
        }).join('');
    },

    /**
     * Side-profile glyph. Direction of each axis is INFERRED from comparing captured
     * positions, not measured against real travel, and the ranges are chosen to read
     * well rather than to be true. Angles stay gentle: past roughly 12 degrees the two
     * clipped halves visibly pull apart at the join, and real positions differ by far
     * less than that.
     */
    glyph(axes, size) {
        const v = (k, d) => (!axes || axes[k] === undefined || axes[k] === this.SENTINEL) ? d : axes[k];
        const n = x => Math.round(x * 100) / 100;
        const backDeg = -(v('BACKREST', 55) - 55) * 0.30;   // negative = more upright
        const tiltDeg = -(v('SITPOINT', 50) - 50) * 0.13;   // negative lifts the front
        const dyPct = (50 - v('HEIGHT', 50)) / 100 * 12;
        const dxPct = -(v('HORIZONTAL', 50) - 50) / 100 * 7;
        const W = size, H = Math.round(size / 0.8443);
        return '<div class="seat" aria-hidden="true" style="width:' + W + 'px;height:' + H + 'px;">' +
            '<div class="seat-body" style="transform:translate(' + n(dxPct) + '%,' + n(dyPct) + '%);">' +
                '<span class="seat-part cushion" style="transform:rotate(' + n(tiltDeg) + 'deg);"></span>' +
                '<span class="seat-part back" style="transform:rotate(' + n(backDeg) + 'deg);"></span>' +
            '</div>' +
            '<span class="seat-floor"></span>' +
        '</div>';
    },

    // Largest per-axis deviation still counted as "this position". The seat does not land
    // exactly on the numbers it is sent — physical actuation has a deadband, so an exact
    // comparison never matches a position that was genuinely just applied. That is what
    // "Not saved as any position" right after a successful apply actually meant.
    //
    // Confirmed by Pål applying several positions and reading each back (2026-08-11): height
    // and fore/aft typically land 1-2 out, the cushion usually 2-3. An earlier single
    // measurement of mine agreed (SITPOINT 93 -> 90) but was weak on its own — it assumed
    // which position had been applied from the fact that it was the closest match.
    //
    // 5 clears the common case with margin and stays far below the smallest real gap between
    // stored positions (14 on LEFT_H for the closest pair here, 33 on SITPOINT between slots
    // 1 and 2, 98 on HEIGHT between 2 and 3), so it cannot blur two positions together.
    MATCH_TOLERANCE: 5,

    /**
     * The stored position the car is currently in, or null. Nearest wins when more than one is
     * within tolerance, so the answer is deterministic rather than list-order dependent.
     */
    sameAs(axes) {
        if (!axes) return null;
        let best = null;
        let bestTotal = Infinity;
        for (const p of this.positions) {
            if (!p.axes) continue;
            let total = 0;
            let within = true;
            for (const a of this.AXES) {
                const stored = (p.axes[a.key] === undefined) ? 0 : p.axes[a.key];
                const live = (axes[a.key] === undefined) ? 0 : axes[a.key];
                const d = Math.abs(stored - live);
                if (d > this.MATCH_TOLERANCE) { within = false; break; }
                total += d;
            }
            if (within && total < bestTotal) { bestTotal = total; best = p; }
        }
        return best;
    },

    renderCurrent() {
        const glyphEl = document.getElementById('spCurrentGlyph');
        const axesEl = document.getElementById('spCurrentAxes');
        const matchEl = document.getElementById('spCurrentMatch');
        if (!glyphEl) return;

        glyphEl.innerHTML = this.current ? this.glyph(this.current, 78) : '';
        axesEl.innerHTML = this.current ? this.axesHtml(this.current) : '';
        if (!this.current) {
            matchEl.textContent = this.t('seatpos.current_unavailable', 'Could not read the seat');
        } else {
            const m = this.sameAs(this.current);
            matchEl.innerHTML = m
                ? this.t('seatpos.matches', 'Matches {0}').replace('{0}', '<em>' + this.esc(m.name) + '</em>')
                : this.esc(this.t('seatpos.not_saved', 'Not saved as any position'));
        }
        document.getElementById('spSaveNew').disabled = !this.acc || !this.current;
    },

    renderGate() {
        // Reading and capturing are safe on any car, so the notice explains rather than warns:
        // the list is the instrument that tells an owner whether the addresses fit their car.
        const unconf = document.getElementById('spUnconfirmed');
        if (unconf) {
            unconf.style.display = this.modelConfirmed ? 'none' : '';
            const txt = document.getElementById('spUnconfirmedText');
            if (txt && !this.modelConfirmed) {
                txt.textContent = this.modelId
                    ? this.t('seatpos.unconfirmed_note', 'These addresses are confirmed on a BYD Seal. Reading and saving positions is safe on any model — capture one, move the seat, capture another, and see whether the values follow. Applying asks first.')
                    : this.t('seatpos.unconfirmed_note_unset', 'No vehicle model is selected in Settings, so this car is treated as unknown. Reading and saving positions is safe — capture one, move the seat, capture another, and see whether the values follow. Applying asks first.');
            }
        }
        const gate = document.getElementById('spGate');
        const moving = document.getElementById('spMoving');
        const badge = document.getElementById('spStateBadge');
        gate.style.display = this.acc ? 'none' : '';
        // Moving is NOT a blocker — the write goes out with the gate overridden, because
        // whether the car honours a seat move in motion is an open question and the only
        // way to answer it is to try. ACC off IS a blocker: unpowered motors accept the
        // write and do nothing, which would otherwise read as success.
        moving.style.display = (this.acc && this.movementBlocked) ? '' : 'none';
        if (!this.acc) {
            badge.className = 'status-badge inactive';
            badge.textContent = this.t('seatpos.state_acc_off', 'ACC off');
        } else if (this.movementBlocked) {
            badge.className = 'status-badge';
            badge.textContent = this.t('seatpos.state_moving', 'Not in Park');
        } else {
            badge.className = 'status-badge active';
            badge.textContent = this.t('seatpos.state_parked', 'Parked');
        }
    },

    rowHtml(p) {
        const isUser = p.source === 'user';
        const uses = this.usedBy(p.id);
        const applied = this.appliedId === p.id;
        return '<div class="sp-row' + (applied ? ' is-applied' : '') + '" data-id="' + this.esc(p.id) + '">' +
            '<div class="seat-frame row-size">' + this.glyph(p.axes, 42) + '</div>' +
            '<div class="sp-row-main">' +
                '<div class="sp-name-line"><span class="sp-name">' + this.esc(p.name) + '</span>' +
                    (isUser ? '' : '<span class="sp-chip from-car">' +
                        this.esc(this.t('seatpos.from_car', 'From car')) + ' · ' +
                        this.esc(this.t('seatpos.slot', 'slot')) + ' ' + this.esc(p.slot) + '</span>') +
                    (applied ? '<span class="sp-chip applied">' + this.esc(this.t('seatpos.applied', 'Applied')) + '</span>' : '') +
                    (uses.length ? '<button class="sp-chip used-by" data-act="usedBy">' + this.ICONS.bolt +
                        this.esc(this.usesLabel(uses.length)) + '</button>' : '') +
                '</div>' +
                // Only when renamed: the alias replaces the car's name in the title, and the
                // slot chip alone ("From car · slot 2") does not say which stored position
                // that is. Shown here so an aliased row can still be matched against what the
                // car's own UI calls it.
                (p.carName ? '<div class="sp-carname">' + this.esc(p.carName) + '</div>' : '') +
                this.partsSummaryHtml(p) +
                '<div class="sp-axes">' + this.axesHtml(p.axes) + '</div>' +
            '</div>' +
            '<div class="sp-row-actions">' +
                (isUser ? '<button class="btn btn-secondary" data-act="saveHere"' + (this.acc ? '' : ' disabled') + '>' +
                    this.esc(this.t('seatpos.save_here', 'Save here')) + '</button>' : '') +
                '<button class="btn btn-primary" data-act="apply"' + ((this.acc && !this.movementBlocked) ? '' : ' disabled') + '>' +
                    this.esc(this.t('seatpos.apply', 'Apply')) + '</button>' +
                '<div class="sp-menu-wrap">' +
                    '<button class="btn btn-secondary icon-btn" data-act="menu" aria-haspopup="true" aria-expanded="false">' +
                        this.ICONS.dots + '</button>' +
                    '<div class="sp-menu">' +
                        '<button data-act="useInAutomation">' + this.ICONS.link +
                            this.esc(this.t('seatpos.use_in_automation', 'Use in an automation')) + '</button>' +
                        // Colour is the one stored value worth picking directly: it comes from
                        // a fixed palette, so choosing one is not the same as typing a seat
                        // pose nobody chose. Only offered where there is ambient to change.
                        (p.ambient ? '<button data-act="ambientColour">' + this.ICONS.pencil +
                            this.esc(this.t('seatpos.change_colour', 'Change the colour')) + '</button>' : '') +
                        (isUser ? '<div class="sp-menu-sep"></div>' +
                            '<button data-act="rename">' + this.ICONS.pencil +
                                this.esc(this.t('seatpos.rename', 'Rename')) + '</button>' +
                            '<button class="danger" data-act="delete">' + this.ICONS.trash +
                                this.esc(this.t('seatpos.delete', 'Delete')) + '</button>'
                        // A captured entry's name belongs to the car and comes back on every
                        // capture, so it gets an alias instead of a rename. Clearing is a
                        // separate item rather than "save an empty name": the entry always has
                        // the car's name to fall back to, so this is a revert, not a delete.
                        : '<div class="sp-menu-sep"></div>' +
                            '<button data-act="alias">' + this.ICONS.pencil +
                                this.esc(this.t('seatpos.set_alias', 'Rename in OverDrive')) + '</button>' +
                            (p.carName ? '<button data-act="clearAlias">' + this.ICONS.undo +
                                this.esc(this.t('seatpos.clear_alias', 'Use the car’s name')) +
                                '</button>' : '')) +
                    '</div>' +
                '</div>' +
            '</div>' +
        '</div>';
    },

    usesLabel(n) {
        return n === 1
            ? this.t('seatpos.used_by_one', 'Used by 1 automation')
            : this.t('seatpos.used_by_many', 'Used by {0} automations').replace('{0}', n);
    },

    groupHead(title, meta) {
        return '<div class="sp-group-head"><span class="sp-group-title">' + this.esc(title) + '</span>' +
            (meta ? '<span class="sp-group-meta">' + this.esc(meta) + '</span>' : '') +
            '<span class="sp-group-rule"></span></div>';
    },

    render() {
        this.renderGate();
        this.renderCurrent();

        let html = '';
        const mine = this.positions.filter(p => p.source === 'user');
        html += this.groupHead(this.t('seatpos.my_positions', 'My positions'));
        html += mine.length
            ? mine.map(p => this.rowHtml(p)).join('')
            : '<div class="sp-empty">' + this.esc(this.t('seatpos.empty',
                'No saved positions yet. Adjust the seat and mirrors, then press Save as new.')) + '</div>';

        // Captured positions are per-profile: the same slot number is different geometry
        // for a different signed-in account, so they group by profile rather than merging.
        const profiles = [];
        this.positions.forEach(p => {
            if (p.source === 'captured' && profiles.indexOf(p.profile) === -1) profiles.push(p.profile);
        });
        profiles.forEach(prof => {
            html += this.groupHead(this.t('seatpos.car_profile', 'Car profile') + ' · ' + prof);
            html += this.positions.filter(p => p.source === 'captured' && p.profile === prof)
                .map(p => this.rowHtml(p)).join('');
        });

        document.getElementById('spList').innerHTML = html;
    },

    // ── actions ─────────────────────────────────────────────────────────────────

    closeMenus(except) {
        document.querySelectorAll('.sp-menu.open').forEach(m => {
            if (m === except) return;
            m.classList.remove('open');
            const t = m.parentNode.querySelector('[data-act="menu"]');
            if (t) t.setAttribute('aria-expanded', 'false');
        });
    },

    usesHtml(uses) {
        return '<div class="sp-uses">' + uses.map(u =>
            '<div class="sp-use"><span class="sp-use-name">' + this.esc(u.name) + '</span></div>').join('') + '</div>';
    },

    onListClick(e) {
        const btn = e.target.closest('button[data-act]');
        if (!btn) return;
        const row = btn.closest('.sp-row');
        const id = row.getAttribute('data-id');
        const p = this.positions.find(x => x.id === id);
        if (!p) return;
        const act = btn.dataset.act;

        if (act === 'menu') {
            const menu = row.querySelector('.sp-menu');
            const open = !menu.classList.contains('open');
            this.closeMenus(menu);
            menu.classList.toggle('open', open);
            btn.setAttribute('aria-expanded', String(open));
            return;
        }
        this.closeMenus(null);

        if (act === 'apply') return this.apply(p);
        if (act === 'usedBy') return this.showUses(p);
        if (act === 'useInAutomation') return this.useInAutomation(p);
        if (act === 'saveHere') return this.saveHere(p);
        if (act === 'rename') return this.rename(p);
        if (act === 'alias') return this.setAlias(p);
        if (act === 'clearAlias') return this.clearAlias(p);
        if (act === 'ambientColour') return this.ambientColour(p);
        if (act === 'delete') return this.remove(p);
    },

    async post(url) {
        const r = await fetch(url, { method: 'POST' });
        return r.json();
    },

    toast(msg, kind) {
        if (window.BYD && BYD.utils && BYD.utils.toast) BYD.utils.toast(msg, kind || 'success');
    },

    async apply(p) {
        if (!this.acc || this.movementBlocked) return;

        // The axis ids are confirmed on a Seal only. On any other model — or a car where the
        // owner never picked one — ask before the first write rather than either writing
        // silently or refusing outright. Refusing would freeze the confirmed-model list at the
        // one car it was written on, since nobody could ever produce the evidence to extend it.
        // Asked here rather than off the endpoint's needsModelAck reply so the batch indicator
        // never animates a write that did not happen; the server check remains the authority
        // for the automation path and any direct API caller.
        let ack = '';
        if (!this.modelConfirmed && !this.modelAcknowledged) {
            const ok = await this.confirm(
                this.t('seatpos.unconfirmed_title', 'Not confirmed for this car'),
                this.t('seatpos.unconfirmed_body',
                    'Seat and mirror positions are read using addresses confirmed on a BYD Seal, and your car may use different ones. Every value being sent was read back from this same car when the position was captured, and the car must be parked, so the likely worst case is that something other than the seat moves slightly. Worth checking the numbers first: capture a position, move the seat, capture another, and see whether the values follow the seat.'),
                '', false, this.t('seatpos.unconfirmed_confirm', 'Apply anyway'));
            if (!ok) return;
            ack = '&ack=YES';
            this.modelAcknowledged = true;
        }

        const host = document.getElementById('spBatches');
        const b1 = document.getElementById('spBatch1');
        const b2 = document.getElementById('spBatch2');
        host.style.display = '';
        b1.className = 'sp-batch run';
        b2.className = 'sp-batch';
        // No force. Settled on the car (2026-08-11): the VEHICLE refuses the write unless the
        // gear is in P — not a motion gate, it refuses with the brake pressed and the car
        // standing still — and shows its own warning. So the block is below OverDrive, not
        // applyFull's gate, and overriding only buys a doomed write plus a native popup.
        const url = '/api/positions/apply?id=' + encodeURIComponent(p.id) + ack;
        const res = await this.post(url).catch(() => null);
        b1.className = 'sp-batch done';
        b2.className = 'sp-batch done';
        setTimeout(() => { host.style.display = 'none'; }, 900);

        if (!res || res.error || res.skipped || res.needsModelAck) {
            this.toast((res && (res.error || res.reason)) || this.t('seatpos.apply_failed', 'Could not apply the position'), 'error');
            return;
        }
        this.appliedId = p.id;
        this.toast(this.t('seatpos.applied_toast', 'Applied {0}').replace('{0}', p.name));
        await this.loadCurrent();
        this.render();
    },

    /**
     * The three save options, as radios inside the shared dialog. Ambient is offered only
     * when the car actually reported some — an option that always fails is worse than an
     * option that is not there.
     */
    partsHtml(selected) {
        const opts = [
            ['all', this.t('seatpos.parts_all', 'Everything'),
                    this.t('seatpos.parts_all_hint', 'Seat, mirrors and the interior lighting')],
            ['geometry', this.t('seatpos.parts_geometry', 'Seat & mirrors'),
                    this.t('seatpos.parts_geometry_hint', 'The position the car is in right now')],
            ['ambient', this.t('seatpos.parts_ambient', 'Ambient light'),
                    this.t('seatpos.parts_ambient_hint', 'Colour, brightness and the light modes')]
        ].filter(o => o[0] === 'geometry' || this.currentAmbient);
        return '<div class="sp-parts">' + opts.map(([v, label, hint]) =>
            '<label class="sp-part"><input type="radio" name="spParts" value="' + v + '"' +
                (v === (selected || 'all') ? ' checked' : '') + '>' +
            '<span class="sp-part-text"><span class="sp-part-label">' + this.esc(label) + '</span>' +
            '<span class="sp-part-hint">' + this.esc(hint) + '</span></span></label>').join('') + '</div>';
    },

    chosenParts() {
        const el = document.querySelector('input[name="spParts"]:checked');
        return el ? el.value : 'all';
    },

    async saveAsNew() {
        const name = await this.prompt(this.t('seatpos.save_new_title', 'Save current position'),
            this.t('seatpos.save_new_body', 'Saves what the car is set to right now.'), '',
            this.partsHtml('all'));
        if (!name) return;
        const parts = this.chosenParts();
        const res = await this.post('/api/positions/create?name=' + encodeURIComponent(name) +
            '&parts=' + encodeURIComponent(parts)).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.save_failed', 'Could not save the position'), 'error');
            return;
        }
        this.toast(this.t('seatpos.saved_toast', 'Saved {0}').replace('{0}', name));
        this.load();
    },

    async saveHere(p) {
        const uses = this.usedBy(p.id);
        const ok = await this.confirm(
            this.t('seatpos.overwrite_title', 'Overwrite {0}?').replace('{0}', p.name),
            this.t('seatpos.overwrite_body', 'What the car is set to right now replaces what is stored under this name. Anything you do not save here is left as it was. This cannot be undone.') +
                (uses.length ? ' ' + this.t('seatpos.overwrite_used', 'These automations will start applying the new settings.') : ''),
            this.partsHtml('all') + (uses.length ? this.usesHtml(uses) : ''));
        if (!ok) return;
        const res = await this.post('/api/positions/save?id=' + encodeURIComponent(p.id) +
            '&parts=' + encodeURIComponent(this.chosenParts())).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.save_failed', 'Could not save the position'), 'error');
            return;
        }
        this.toast(this.t('seatpos.saved_toast', 'Saved {0}').replace('{0}', p.name));
        this.load();
    },

    async rename(p) {
        const name = await this.prompt(this.t('seatpos.rename_title', 'Rename position'), '', p.name);
        if (!name || name === p.name) return;
        const res = await this.post('/api/positions/rename?id=' + encodeURIComponent(p.id) +
            '&name=' + encodeURIComponent(name)).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.rename_failed', 'Could not rename the position'), 'error');
            return;
        }
        this.load();
    },

    /**
     * Captured entries are rebuilt from the car on every capture, so their name cannot be edited
     * in place — the alias is stored beside it and survives. Prefilled with whatever is showing:
     * the current alias, or the car's name as a starting point when there is none yet.
     */
    async setAlias(p) {
        const alias = await this.prompt(
            this.t('seatpos.alias_title', 'Rename in OverDrive'),
            this.t('seatpos.alias_body', 'The car keeps calling it {0}. This name is only used in OverDrive.')
                .replace('{0}', p.carName || p.name),
            p.name);
        if (alias === null || alias === p.name) return;
        const res = await this.post('/api/positions/alias?id=' + encodeURIComponent(p.id) +
            '&alias=' + encodeURIComponent(alias)).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.alias_failed', 'Could not rename the position'), 'error');
            return;
        }
        this.load();
    },

    async clearAlias(p) {
        const res = await this.post('/api/positions/alias?id=' + encodeURIComponent(p.id) +
            '&alias=').catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.alias_failed', 'Could not rename the position'), 'error');
            return;
        }
        this.load();
    },

    async remove(p) {
        const uses = this.usedBy(p.id);
        const ok = await this.confirm(
            this.t('seatpos.delete_title', 'Delete {0}?').replace('{0}', p.name),
            uses.length
                ? this.t('seatpos.delete_used', 'These automations stop working.')
                : this.t('seatpos.delete_unused', 'Nothing references this position.'),
            uses.length ? this.usesHtml(uses) : '', true);
        if (!ok) return;
        const res = await this.post('/api/positions/delete?id=' + encodeURIComponent(p.id)).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.delete_failed', 'Could not delete the position'), 'error');
            return;
        }
        if (this.appliedId === p.id) this.appliedId = null;
        this.load();
    },

    showUses(p) {
        const uses = this.usedBy(p.id);
        this.confirm(this.usesLabel(uses.length),
            this.t('seatpos.used_by_body', 'These automations apply this position. They are edited on the Automations page.'),
            this.usesHtml(uses), false, this.t('seatpos.open_automations', 'Open Automations'))
            .then(ok => { if (ok) window.location.href = 'automations.html'; });
    },

    useInAutomation(p) {
        // Deep-link into the single editor with the action and position pre-filled. A
        // shortcut into the Automations page, never a second editor here.
        this.confirm(this.t('seatpos.use_title', 'Use in an automation'),
            this.t('seatpos.use_body', 'Opens the Automations page with a new rule, action set to Apply Seat & Mirror Position and position set to {0}.').replace('{0}', p.name),
            '', false, this.t('seatpos.open_automations', 'Open Automations'))
            .then(ok => {
                if (ok) window.location.href = 'automations.html?action=applySeatPosition&id=' + encodeURIComponent(p.id);
            });
    },

    /**
     * What a position actually stores. Shown because two positions can now differ in kind,
     * not just in value — "why did applying this not move the seat" is answered here rather
     * than by applying it and watching nothing happen.
     */
    partsSummaryHtml(p) {
        const bits = [];
        if (p.axes && Object.keys(p.axes).length) bits.push(this.t('seatpos.parts_geometry', 'Seat & mirrors'));
        if (p.ambient) {
            const c = this.ambientColourOf(p);
            bits.push(this.t('seatpos.parts_ambient', 'Ambient light') +
                (c ? ' <span class="sp-swatch" style="background:' + this.esc(this.paletteHex(c)) + '"></span>' : ''));
        }
        if (!bits.length) return '';
        return '<div class="sp-parts-summary">' + bits.join('<span class="sp-dot">·</span>') + '</div>';
    },

    /** The stored colour, front first — the zone a person looks at. */
    ambientColourOf(p) {
        const a = p && p.ambient;
        if (!a) return null;
        const f = a.front && a.front.colour;
        const r = a.rear && a.rear.colour;
        return f || r || null;
    },

    /** Palette is 1-based on the car; the array is not. */
    paletteHex(colour) {
        const i = Number(colour) - 1;
        return (this.palette && this.palette[i]) ? this.palette[i] : 'transparent';
    },

    /**
     * The colour ramp as a CSS gradient. Used as the slider's track so the control reads as
     * a continuous colour picker, which is what the car's own screen looks like.
     *
     * <p>The stop COUNT and the hex table are deliberately independent. The car exposes
     * 6, 30, 63 or 126 colours depending on trim, and this app only knows the hex values for
     * the 30-colour ramp — so a swatch per colour cannot be drawn on a bigger palette, while
     * a gradient can: it shows the ramp, and the slider position picks the index along it.
     */
    paletteGradient() {
        const cols = (this.palette && this.palette.length) ? this.palette : ['#000000', '#FFFFFF'];
        const stops = cols.map((hex, i) =>
            hex + ' ' + ((i / Math.max(1, cols.length - 1)) * 100).toFixed(2) + '%');
        return 'linear-gradient(90deg, ' + stops.join(', ') + ')';
    },

    async ambientColour(p) {
        const max = this.colourMax || 30;
        const current = this.ambientColourOf(p) || 1;
        const html =
            '<div class="sp-colour-pick">' +
                '<input type="range" id="spColour" class="sp-colour-range" min="1" max="' + max +
                    '" value="' + current + '" style="background:' + this.esc(this.paletteGradient()) + '">' +
                '<div class="sp-colour-meta">' +
                    '<span id="spColourNum">' + current + '</span>' +
                    '<span class="sp-colour-of">/ ' + max + '</span>' +
                '</div>' +
            '</div>';
        const ok = await this.confirm(
            this.t('seatpos.colour_title', 'Change the colour'),
            this.t('seatpos.colour_body', 'Sets the colour stored on this position. The car is not changed until the position is applied.'),
            html, false, this.t('seatpos.save', 'Save'));
        if (!ok) return;
        const slider = document.getElementById('spColour');
        if (!slider) return;
        const chosen = slider.value;
        if (Number(chosen) === Number(this.ambientColourOf(p))) return;
        const res = await this.post('/api/positions/ambient-colour?id=' + encodeURIComponent(p.id) +
            '&colour=' + encodeURIComponent(chosen)).catch(() => null);
        if (!res || res.error) {
            this.toast((res && res.error) || this.t('seatpos.colour_failed', 'Could not change the colour'), 'error');
            return;
        }
        this.load();
    },

    // ── dialogs ─────────────────────────────────────────────────────────────────

    _dialog(title, body, html, opts) {
        return new Promise(resolve => {
            const scrim = document.getElementById('spScrim');
            document.getElementById('spDialogTitle').textContent = title;
            const bodyEl = document.getElementById('spDialogBody');
            bodyEl.textContent = body || '';
            bodyEl.style.display = body ? '' : 'none';
            const htmlEl = document.getElementById('spDialogHtml');
            htmlEl.innerHTML = html || '';
            htmlEl.style.display = html ? '' : 'none';
            const input = document.getElementById('spDialogInput');
            input.style.display = opts.prompt ? '' : 'none';
            input.value = opts.value || '';
            const confirmBtn = document.getElementById('spDialogConfirm');
            confirmBtn.textContent = opts.confirmLabel;
            confirmBtn.className = 'btn ' + (opts.danger ? 'btn-danger' : 'btn-primary');

            // .modal-backdrop is display:flex in styles.css and pages toggle it inline
            // (see performance.html's SOH modal) — there is no .show class for it.
            const done = (value) => {
                scrim.style.display = 'none';
                confirmBtn.onclick = null;
                document.getElementById('spDialogCancel').onclick = null;
                document.onkeydown = null;
                resolve(value);
            };
            confirmBtn.onclick = () => done(opts.prompt ? (input.value.trim() || null) : true);
            document.getElementById('spDialogCancel').onclick = () => done(null);
            document.onkeydown = (e) => {
                if (e.key === 'Escape') done(null);
                if (e.key === 'Enter' && opts.prompt) { e.preventDefault(); confirmBtn.click(); }
            };
            scrim.style.display = 'flex';
            setTimeout(() => (opts.prompt ? input.focus() : confirmBtn.focus()), 30);
        });
    },

    prompt(title, body, value, html) {
        return this._dialog(title, body, html || '', {
            prompt: true, value: value,
            confirmLabel: this.t('seatpos.save', 'Save')
        });
    },

    confirm(title, body, html, danger, confirmLabel) {
        return this._dialog(title, body, html, {
            danger: danger,
            confirmLabel: confirmLabel || this.t('seatpos.confirm', 'Confirm')
        });
    }
};

window.SeatPositions = SeatPositions;
