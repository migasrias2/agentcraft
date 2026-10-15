// Foremans that tests start as child processes inherit this, so prompts and feed lines are
// deterministic regardless of the OS account name.
process.env.AGENTCRAFT_USER_NAME = 'Alex';
// The social and content calendars use this zone (default: the machine's); the tests pin it.
process.env.AGENTCRAFT_TZ = 'Europe/Lisbon';
