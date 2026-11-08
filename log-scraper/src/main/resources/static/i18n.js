/* Log Scraper language support: Bahasa Indonesia (default) and English.
 * The pages are written in Bahasa; LS.t() looks a text up in EN when English is chosen.
 * The choice is kept in the browser (localStorage) and in the URL (?lang=en), so it also works where storage is blocked.
 * Every request to the API carries X-Lang, so messages written by the server come back in the same language. */
(function () {
  'use strict';
  const EN = {
    // ---- shared / navigation
    'Menu utama': 'Main menu', 'Pencarian': 'Search', 'Pengaturan': 'Settings',
    'Pengaturan · Log Scraper': 'Settings · Log Scraper',
    'Waktu': 'Time', 'Pesan': 'Message', 'baris': 'lines', 'detik': 'seconds', 'dtk': 's', '+detik': '+sec',
    'Hapus': 'Delete', 'Tambah': 'Add', 'Simpan': 'Save', 'Batal': 'Cancel', 'Coba': 'Try', 'Cek': 'Check', 'Ubah': 'Edit',
    'Tersalin': 'Copied', 'Tersalin ✓': 'Copied ✓', 'disamarkan': 'masked',

    // ---- search page
    'Satu ID, semua server, satu timeline. Data pribadi disamarkan otomatis, tanpa instalasi apa pun di server.':
      'One ID, every server, one timeline. Personal data is masked automatically, with nothing installed on the servers.',
    'ID request, order atau tiket, contoh REQ-88231': 'Request, order or ticket ID, e.g. REQ-88231',
    'ID yang dicari': 'ID to search for', 'Rentang waktu': 'Time range',
    '1 jam terakhir': 'Last 1 hour', '6 jam terakhir': 'Last 6 hours', '24 jam terakhir': 'Last 24 hours', '7 hari terakhir': 'Last 7 days',
    'Cari': 'Search', 'Bersihkan': 'Clear',
    'Tempel ID dari laporan pengguna atau tiket. Spasi dan tanda baca di ujung dibersihkan otomatis.':
      'Paste an ID from a user report or a ticket. Spaces and punctuation at the ends are removed automatically.',
    'Perjalanan request': 'Request journey', 'Satu jalur per server. Merah = ERROR, kuning = WARN.': 'One lane per server. Red = ERROR, yellow = WARN.',
    'Timeline': 'Timeline', 'Tampilan timeline': 'Timeline view', 'Per server': 'Per server', 'Satu timeline': 'Single timeline',
    'Sembunyikan DEBUG': 'Hide DEBUG', 'Salin untuk tiket': 'Copy for ticket',
    'Clipboard diblokir browser. Pilih semua teks di bawah lalu salin manual.': 'The browser blocked the clipboard. Select all the text below and copy it yourself.',
    'nomor kartu': 'card number', 'nomor telepon': 'phone number', 'rahasia (token, password, OTP)': 'secret (token, password, OTP)',
    'nomor akun': 'account number', 'nama': 'name', 'alamat': 'address', 'tanggal lahir': 'birth date', 'CVV kartu': 'Card CVV',
    'alamat IP': 'IP address', 'data kustom': 'custom data',
    'baris ditemukan': 'lines found', 'tidak ada baris': 'no lines', 'tidak terjangkau': 'unreachable', 'login gagal': 'login failed',
    'perintah ditolak': 'command refused', 'kunci server berubah': 'server key changed', 'folder log belum diatur': 'log folder not set',
    'format log tidak dikenali': 'log format not recognized',
    'Coba:': 'Try:', 'Masukkan ID atau kata yang dicari.': 'Enter an ID or a word to search for.',
    'Menghubungi semua server lewat SSH read-only…': 'Contacting every server over read-only SSH…',
    'Jawaban server tidak bisa dibaca.': 'The server reply could not be read.', 'Pencarian gagal (HTTP {s}).': 'Search failed (HTTP {s}).',
    'Log Scraper tidak bisa dihubungi. Cek apakah aplikasinya masih jalan.': 'Log Scraper cannot be reached. Check that the app is still running.',
    'Atur server': 'Set up server', 'ADA ERROR': 'ERROR FOUND', 'SELESAI NORMAL': 'COMPLETED NORMALLY', 'HASIL PENCARIAN': 'SEARCH RESULTS',
    'TIDAK DITEMUKAN': 'NOT FOUND', 'Mungkin maksudnya:': 'Did you mean:',
    'Coba perlebar rentang waktu, atau cek lagi ID-nya di tiket.': 'Try a wider time range, or check the ID in the ticket again.',
    'Tidak ada log untuk "{q}" di {n} server yang terjangkau.': 'No logs for "{q}" on the {n} servers that could be reached.',
    'Log Scraper, {n} server sekaligus. Cara manual: ±{m} menit (SSH dan grep satu per satu).':
      'Log Scraper, {n} servers at once. By hand: about {m} minutes (SSH and grep one by one).',
    'digabung jadi satu timeline dari {n} server': 'merged into one timeline from {n} servers',
    '{n} data disamarkan': '{n} values masked',
    'Nomor referensi yang bukan NIK atau kartu tetap terbaca.': 'Reference numbers that are not an NIK or a card number stay readable.',
    'Tidak ada data pribadi di hasil ini.': 'No personal data in these results.',
    'Belum ada server yang diatur, jadi tidak ada yang dicari.': 'No servers are set up yet, so nothing was searched.',
    'Buka Pengaturan': 'Open Settings', 'untuk menambahkan yang pertama.': 'to add the first one.',
    'Hasil mungkin tidak lengkap: {d} dari {n} server bermasalah.': 'Results may be incomplete: {d} of {n} servers had a problem.',
    'Dicari di {n} server, semuanya menjawab.': 'Searched {n} servers, all of them answered.',
    '{n} baris stack trace': '{n} stack trace lines', 'Tidak ada baris.': 'No lines.', 'Timeline ({n} baris)': 'Timeline ({n} lines)',
    'Contoh baris yang tidak dikenali (data pribadi sudah disamarkan)': 'Sample lines that were not recognized (personal data already masked)',
    's/d': 'to', 'Hasil:': 'Result:', 'PERHATIAN:': 'ATTENTION:', 'Server yang dicari ({n}):': 'Servers searched ({n}):',
    '(tidak ada baris)': '(no lines)', 'Data pribadi sudah disamarkan otomatis.': 'Personal data has been masked automatically.',

    // ---- settings page
    'Hubungkan server, tentukan folder lognya, dan cek formatnya. Password hanya bisa ditulis, tidak pernah ditampilkan lagi.':
      'Connect servers, choose their log folders and check the format. Passwords can only be written, never shown again.',
    'Token admin': 'Admin token',
    'Pengaturan ini dilindungi token (SETUP_TOKEN). Token hanya disimpan di tab ini sampai ditutup.':
      'These settings are protected by a token (SETUP_TOKEN). The token is kept only in this tab until it is closed.',
    'Masuk': 'Sign in', 'Server': 'Servers', 'Server ({n})': 'Servers ({n})', '+ Tambah server': '+ Add server', 'Belum ada server. Tambahkan yang pertama.': 'No servers yet. Add the first one.',
    'Tambah server': 'Add server', 'Nama': 'Name', 'Peran': 'Role', 'Username (akun read-only)': 'Username (read-only account)',
    'kosongkan jika pakai kunci SSH': 'leave empty when using an SSH key',
    'Penyamaran data': 'Data masking',
    'Data pribadi disamarkan sebelum tampil di hasil pencarian. Matikan filter hanya kalau data itu memang tidak ada di log Anda. Halaman pencarian tidak bisa mengubah ini.':
      'Personal data is masked before it appears in search results. Turn a filter off only when that data really is not in your logs. The search page cannot change this.',
    'Nama field tambahan (satu per baris)': 'Extra field names (one per line)',
    'Nilai setelah': 'The value after', 'atau': 'or', 'akan disamarkan.': 'will be masked.',
    'Aturan regex sendiri': 'Your own regex rules',
    'Dua angka di kanan: jumlah karakter awal dan akhir yang tetap terlihat.': 'The two numbers on the right: how many characters at the start and end stay visible.',
    '+ Tambah aturan': '+ Add rule', 'Coba dengan contoh baris log': 'Try it on a sample log line', 'Simpan penyamaran': 'Save masking',
    'Bahasa': 'Language',
    'Bahasa tampilan Log Scraper di browser ini, termasuk pesan dari server. Log asli tidak diterjemahkan.':
      'The language Log Scraper uses in this browser, including messages from the server. The logs themselves are not translated.',
    'Token admin diperlukan': 'Admin token required', 'Gagal (HTTP {s})': 'Failed (HTTP {s})',
    'password tersimpan': 'password saved', 'kunci SSH': 'SSH key', 'belum ada password atau kunci': 'no password or key yet',
    'Lihat format': 'View format',
    'Folder log belum diatur. Cari dari sebuah ID di bawah, atau tambahkan sendiri.': 'No log folder set yet. Find one from an ID below, or add it yourself.',
    'identitas server dikunci': 'server identity pinned', 'identitas server belum dikunci': 'server identity not pinned',
    'Tes koneksi': 'Test connection', 'Buat kunci SSH': 'Create SSH key', 'Folder log': 'Log folders',
    'Cari folder log dari sebuah ID': 'Find log folders from an ID',
    'ID request yang terjadi hari ini, contoh REQ-88231': 'A request ID from today, e.g. REQ-88231', 'Cari folder': 'Find folders',
    'Log Scraper mencari ID itu di folder umum dan menunjukkan file mana yang memuatnya.': 'Log Scraper looks for that ID in the usual folders and shows which files contain it.',
    'Kunci identitas server ini': "Pin this server's identity",
    'Cocokkan dulu {fp} dengan yang diberikan tim server. Setelah dikunci, server dengan kunci lain ditolak.':
      'First compare {fp} with the one from the server team. Once pinned, a server presenting another key is refused.',
    'Koneksi berhasil': 'Connection works', 'Koneksi belum berhasil': 'Connection not working yet',
    'Kunci SSH dibuat.': 'SSH key created.',
    'Kunci pribadi tetap di Log Scraper. Minta tim server menjalankan ini sebagai akun read-only:': 'The private key stays in Log Scraper. Ask the server team to run this as the read-only account:',
    'Salin perintah': 'Copy command', 'Cocok dengan {n} file': 'Matches {n} files', 'Tidak ada file yang cocok': 'No matching files',
    '{r} dari {n} baris terbaca ({p}%)': '{r} of {n} lines understood ({p}%)', 'Contoh baris yang tidak dikenali:': 'Sample lines that were not recognized:',
    'Disamarkan di pratinjau:': 'Masked in the preview:', 'Tidak ada hasil.': 'No results.', '{n} file .gz (belum dicari)': '{n} .gz files (not searched yet)',
    'sudah dipakai': 'in use', 'Pakai': 'Use', 'Ditemukan {n} lokasi log': 'Found {n} log locations',
    '{n} log sistem disembunyikan.': '{n} system logs hidden.', '{n} folder tidak boleh dibaca akun ini.': '{n} folders this account may not read.',
    'Clipboard diblokir browser. Salin manual.': 'The browser blocked the clipboard. Copy it yourself.',
    'Hapus server {n} dari Log Scraper? Server itu sendiri tidak diubah.': 'Remove server {n} from Log Scraper? The server itself is not changed.',
    'Server dihapus': 'Server removed',
    'Server ini sudah punya kunci. Buat yang baru? Kunci lama tidak berlaku lagi.': 'This server already has a key. Create a new one? The old key stops working.',
    'Identitas server dikunci': 'Server identity pinned', 'Folder log disimpan': 'Log folder saved', 'Isi ID request dulu': 'Enter a request ID first',
    'Ubah {n}': 'Edit {n}',
    'Password sudah tersimpan dan tidak bisa dilihat. Kosongkan kolom ini jika tidak ingin menggantinya.': 'A password is saved and cannot be viewed. Leave this empty to keep it.',
    'Perubahan disimpan': 'Changes saved', 'Server disimpan. Lanjut: Tes koneksi.': 'Server saved. Next: Test connection.',
    'NIK (16 digit, dicek provinsi dan tanggal lahir)': 'NIK (16 digits, province and birth date checked)', 'Nomor kartu (lolos cek Luhn)': 'Card number (passes the Luhn check)',
    'Nomor telepon': 'Phone number', 'Nomor akun / VA': 'Account / VA number', 'Nama orang': "People's names", 'Alamat': 'Address',
    'Tanggal lahir': 'Birth date', 'Alamat IP': 'IP address', 'Field tambahan dan regex sendiri': 'Extra fields and your own regex',
    'Nama aturan': 'Rule name', 'Regex, contoh': 'Regex, e.g.', 'Karakter awal yang dibiarkan': 'Characters kept at the start',
    'Karakter akhir yang dibiarkan': 'Characters kept at the end', '{n} filter dimatikan': '{n} filters off', 'Semua filter aktif': 'All filters on',
    'Penyamaran disimpan. Berlaku di pencarian berikutnya.': 'Masking saved. It applies from the next search.',

    // ---- filters, paging, many servers
    'Saring timeline': 'Filter the timeline', 'Semua server': 'All servers', 'Saring server': 'Filter by server', 'Saring level': 'Filter by level',
    'Semua level': 'All levels', 'ERROR saja': 'ERROR only', 'WARN dan ERROR': 'WARN and ERROR', 'Tanpa DEBUG': 'Without DEBUG',
    'Saring isi pesan…': 'Filter message text…', 'Saring isi pesan': 'Filter message text', 'Baris per halaman': 'Lines per page',
    '10 per halaman': '10 per page', '25 per halaman': '25 per page', '50 per halaman': '50 per page', '100 per halaman': '100 per page',
    'Hapus saringan': 'Clear filters', 'Tampilkan lebih sedikit': 'Show fewer', 'Tampilkan semua {n} server': 'Show all {n} servers',
    '{n} server lain menjawab normal.': '{n} other servers answered normally.', '{n} server tanpa baris tidak ditampilkan.': '{n} servers without lines are not shown.',
    '{a}–{b} dari {n} baris': '{a}–{b} of {n} lines', 'Sebelumnya': 'Previous', 'Berikutnya': 'Next', 'Halaman {p} dari {n}': 'Page {p} of {n}',
    'Timeline ({n} dari {m} baris)': 'Timeline ({n} of {m} lines)', 'Tidak ada baris yang cocok di:': 'No matching lines on:',
    'Tidak ada baris di:': 'No lines on:', 'Tidak ada baris yang cocok dengan saringan.': 'No lines match the filters.',
    'Cari server: nama, peran atau host': 'Find a server: name, role or host', 'Cari server': 'Find a server',
    'Folder log belum diatur': 'No log folder yet', 'Identitas belum dikunci': 'Identity not pinned', 'Tes koneksi gagal': 'Connection test failed',
    'Menampilkan {n} dari {m} server.': 'Showing {n} of {m} servers.', 'Tidak ada server yang cocok dengan saringan.': 'No servers match the filter.',
    'Tutup': 'Close', 'Detail': 'Details', '{n} folder log': '{n} log folders',

    // ---- static demo
    'Demo dengan data contoh: tidak ada server asli yang dihubungi. Coba ID': 'Demo with sample data: no real server is contacted. Try ID',
    'Simulasikan app-04 mati': 'Simulate app-04 down', 'Nyalakan lagi app-04': 'Bring app-04 back'
  };

  function read() {
    try { const q = new URLSearchParams(location.search).get('lang'); if (q === 'en' || q === 'id') return q; } catch (e) { /* no URL API */ }
    try { const s = window.localStorage.getItem('ls-lang'); if (s === 'en' || s === 'id') return s; } catch (e) { /* storage blocked */ }
    return 'id';
  }
  const lang = read();
  let urlLang = false;
  try { urlLang = !!new URLSearchParams(location.search).get('lang'); } catch (e) { /* ignore */ }

  function t(s, vars) {
    let r = lang === 'en' && Object.prototype.hasOwnProperty.call(EN, s) ? EN[s] : s;
    if (vars) r = r.replace(/\{(\w+)\}/g, (m, k) => (k in vars ? vars[k] : m));
    return r;
  }
  /** A link to another page of the app that keeps the language when storage cannot. */
  function href(page) { return urlLang ? page + (page.includes('?') ? '&' : '?') + 'lang=' + lang : page; }
  function setLang(l) {
    try { window.localStorage.setItem('ls-lang', l); } catch (e) { /* storage blocked: the URL carries it */ }
    const u = new URL(location.href); u.searchParams.set('lang', l);
    location.href = u.toString();
  }

  // Server messages follow the same language.
  if (window.fetch) {
    const orig = window.fetch.bind(window);
    window.fetch = function (url, opts) {
      opts = Object.assign({}, opts || {});
      const h = new Headers(opts.headers || {}); h.set('X-Lang', lang); opts.headers = h;
      return orig(url, opts);
    };
  }

  /** Translates the text written in the page itself. Text the scripts build is translated where it is built. */
  function translateDom(root) {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: n => (n.parentNode && /^(SCRIPT|STYLE|TEXTAREA|PRE|CODE)$/.test(n.parentNode.nodeName) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT)
    });
    const nodes = []; while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(n => {
      const k = n.nodeValue.trim();
      if (k && Object.prototype.hasOwnProperty.call(EN, k)) n.nodeValue = n.nodeValue.replace(k, EN[k]);
    });
    root.querySelectorAll('[placeholder],[aria-label],[title]').forEach(el => {
      ['placeholder', 'aria-label', 'title'].forEach(a => {
        const v = el.getAttribute(a);
        if (v && Object.prototype.hasOwnProperty.call(EN, v.trim())) el.setAttribute(a, EN[v.trim()]);
      });
    });
  }

  function start() {
    document.documentElement.lang = lang;
    if (lang === 'en') { translateDom(document.body); document.title = t(document.title); }
    if (urlLang) document.querySelectorAll('nav a[href]').forEach(a => {
      const h = a.getAttribute('href');
      if (!/^(https?:|#)/.test(h)) a.setAttribute('href', href(h === './' ? 'index.html' : h));
    });
  }
  // The pages run their own script at the end of <body>; translate right after the markup is parsed.
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();

  window.LS = { lang, t, href, setLang };
})();
