FROM ubuntu:bionic

RUN apt-get update && apt-get install -y apache2

ADD d2.4_433/ssl1.1/dispatcher-apache2.4-4.3.3.so /usr/lib/apache2/modules/mod_dispatcher.so
RUN chmod 644 /usr/lib/apache2/modules/mod_dispatcher.so

ADD dispatcher.any /etc/apache2/conf-enabled/dispatcher.any
ADD dispatcher.conf /etc/apache2/conf-available/dispatcher.conf
RUN a2enconf dispatcher

RUN chown -R www-data /var/www/html

# extra step to enable ssl
RUN mkdir -p /etc/certificate
ADD private.key /etc/certificate/private.key
ADD certificate.crt /etc/certificate/certificate.crt
ADD ssl-params.conf /etc/apache2/conf-available/ssl-params.conf
RUN mv /etc/apache2/sites-available/default-ssl.conf /etc/apache2/sites-available/default-ssl.conf.orig
ADD default-ssl.conf /etc/apache2/sites-available/default-ssl.conf
RUN a2enmod ssl
RUN a2enmod headers
RUN a2enconf ssl-params
RUN a2ensite default-ssl

#CMD apachectl start && tail -F /var/log/apache2/error.log
CMD ["bash"]