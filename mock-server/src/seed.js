'use strict';

// Fictional CRM records with Indian numbers (E.164). Edit freely to match the test device's contacts.
function seedData() {
  const now = Date.now();
  return {
    contacts: [
      { id: 'con_00001', name: 'Ananya Sharma', company: 'Sharma Traders', phone: '+919876543210', createdAt: now },
      { id: 'con_00002', name: 'Rahul Mehta', company: 'Mehta Logistics', phone: '+919812345678', createdAt: now },
      { id: 'con_00003', name: 'Priya Nair', company: 'Nair Textiles', phone: '+918047112233', createdAt: now },
    ],
    leads: [
      { id: 'lead_00001', name: 'Vikram Rao', company: 'Rao Pharma', phone: '+919900112233', source: 'WEB', createdAt: now },
      { id: 'lead_00002', name: 'Sneha Patel', company: 'Patel Foods', phone: '+917012345678', source: 'REFERRAL', createdAt: now },
    ],
    counters: { con: 3, lead: 2 },
  };
}

module.exports = { seedData };
