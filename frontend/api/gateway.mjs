import gateway from '../server/office-gateway.mjs';

export default {
  fetch(request) { return gateway.fetch(request); },
};
